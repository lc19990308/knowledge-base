package dev.aiqa.ingest;

import dev.aiqa.common.FileNames;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.apache.poi.sl.usermodel.TableShape;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;

/** Extracts embedded text only. No OCR, macros, remote references or embedded scripts. */
@Component
public class TextExtractor {

  private final int maxChunkLength;
  private final int chunkOverlap;

  /** 无参构造使用默认切块参数，供单元测试与不依赖 Spring 的直接调用。 */
  public TextExtractor() {
    this(800, 120);
  }

  /**
   * Spring 注入切块配置：默认 800 字窗口、120 字重叠。
   *
   * <p>窗口从 4000 降到 800 是为了让一个向量只概括一段语义，而不是把整页内容平均掉；
   * 重叠保证条款被窗口边界切开时，两侧至少有一侧读到完整句子。
   */
  @org.springframework.beans.factory.annotation.Autowired
  public TextExtractor(
      @org.springframework.beans.factory.annotation.Value("${aiqa.chunking.max-chars:800}")
          int maxChunkLength,
      @org.springframework.beans.factory.annotation.Value("${aiqa.chunking.overlap:120}")
          int chunkOverlap) {
    this.maxChunkLength = Math.max(200, maxChunkLength);
    this.chunkOverlap = Math.max(0, Math.min(chunkOverlap, this.maxChunkLength / 2));
  }
  private static final Pattern MARKDOWN_HEADING =
      Pattern.compile("^ {0,3}#{1,6}[ \\t]+(.+?)(?:[ \\t]+#+)?[ \\t]*$");
  private static final Pattern POLICY_HEADING = Pattern.compile(
      "^第[〇零一二三四五六七八九十百千万两0-9０-９]+[编章节条款](?:.*)$");
  private static final Pattern DOCX_HEADING_STYLE =
      Pattern.compile("(?:heading|标题)\\s*[1-9]", Pattern.CASE_INSENSITIVE);

  public record Chunk(String body, String sourceLocation) {}

  public record Extracted(List<Chunk> chunks) {}

  public Extracted extract(String filename, InputStream content) throws IOException {
    List<Chunk> chunks = new ArrayList<>();
    String extension = FileNames.extension(filename);
    switch (extension) {
      case "pdf" -> pdf(content, chunks);
      case "docx" -> docx(content, chunks);
      case "xlsx" -> xlsx(content, chunks);
      case "pptx" -> pptx(content, chunks);
      case "txt", "md" -> textSections(new String(content.readAllBytes(), StandardCharsets.UTF_8),
          extension.equals("md"), null, chunks);
      default -> throw new IOException("不支持的文件格式");
    }
    return new Extracted(List.copyOf(chunks));
  }

  private void pdf(InputStream content, List<Chunk> chunks) throws IOException {
    try (PDDocument document = Loader.loadPDF(content.readAllBytes())) {
      document.setAllSecurityToBeRemoved(false);
      // One traversal, with a flush at the actual page boundary (including blank pages).
      PDFTextStripper stripper = new PDFTextStripper() {
        private final StringWriter page = new StringWriter();

        @Override
        protected void startPage(PDPage ignored) {
          page.getBuffer().setLength(0);
          output = page;
        }

        @Override
        protected void endPage(PDPage ignored) {
          textSections(page.toString(), false, "第 " + getCurrentPageNo() + " 页", chunks);
        }
      };
      stripper.writeText(document, Writer.nullWriter());
    }
  }

  private void docx(InputStream content, List<Chunk> chunks) throws IOException {
    try (XWPFDocument document = new XWPFDocument(content)) {
      String section = null;
      StringBuilder paragraphs = new StringBuilder();
      int tableIndex = 0;
      for (var element : document.getBodyElements()) {
        if (element instanceof XWPFParagraph paragraph) {
          String body = paragraph.getText().strip();
          if (body.isEmpty()) {
            continue;
          }
          if (isDocxHeading(document, paragraph) || POLICY_HEADING.matcher(body).matches()) {
            flush(paragraphs, section, chunks);
            section = headingLabel(body);
          }
          paragraphs.append(body).append('\n');
        } else if (element instanceof XWPFTable table) {
          flush(paragraphs, section, chunks);
          tableIndex++;
          int rowIndex = 0;
          for (var row : table.getRows()) {
            rowIndex++;
            StringJoiner cells = new StringJoiner("\t");
            row.getTableCells().forEach(cell -> cells.add(cell.getTextRecursively()));
            addTableRow(cells.toString(), locate(section, tableRow(tableIndex, rowIndex)), chunks);
          }
        }
      }
      flush(paragraphs, section, chunks);
    }
  }

  private boolean isDocxHeading(XWPFDocument document, XWPFParagraph paragraph) {
    var properties = paragraph.getCTP().getPPr();
    if (properties != null && properties.isSetOutlineLvl()
        && properties.getOutlineLvl().getVal().intValue() < 9) {
      return true;
    }
    String styleId = paragraph.getStyle();
    if (styleId == null) {
      return false;
    }
    if (DOCX_HEADING_STYLE.matcher(styleId).matches()) {
      return true;
    }
    var styles = document.getStyles();
    var style = styles == null ? null : styles.getStyle(styleId);
    return style != null && style.getName() != null
        && DOCX_HEADING_STYLE.matcher(style.getName()).matches();
  }

  /**
   * 工作表按行切片，并把"最近表头"补进每条数据行。
   *
   * <p>只存裸单元格时，数据行「杭州　A档　620　含税」离开表头就看不出 620 是"上限（元）"，
   * 检索命中后也没法回答"多少钱"。这里把头两行里的标题与列名拼进同一条证据，
   * 并显式标注这是解析器补全的表格上下文，引用仍定位到原始行号。
   */
  private void xlsx(InputStream content, List<Chunk> chunks) throws IOException {
    DataFormatter formatter = new DataFormatter(Locale.ROOT);
    try (Workbook workbook = WorkbookFactory.create(content)) {
      for (Sheet sheet : workbook) {
        List<Integer> rowNumbers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
          List<String> cells = new ArrayList<>();
          for (int column = 0; column < row.getLastCellNum(); column++) {
            cells.add(formatter.formatCellValue(row.getCell(column)).strip());
          }
          rows.add(cells);
          rowNumbers.add(row.getRowNum() + 1);
        }
        int header = headerRow(rows);
        for (int index = 0; index < rows.size(); index++) {
          List<String> cells = rows.get(index);
          if (cells.stream().allMatch(String::isBlank) || index <= header) {
            continue;
          }
          String location = "工作表「" + sheet.getSheetName() + "」第 " + rowNumbers.get(index) + " 行";
          addTableRow(xlsxBody(rows, header, index, cells), location, chunks);
        }
      }
    }
  }

  /** 表头行：至少两个单元格、没有纯数字，且其后存在带数字的数据行。 */
  private int headerRow(List<List<String>> rows) {
    for (int index = 0; index < Math.min(rows.size() - 1, 5); index++) {
      List<String> cells = rows.get(index).stream().filter(cell -> !cell.isBlank()).toList();
      if (cells.size() < 2 || cells.stream().anyMatch(TextExtractor::looksNumeric)) {
        continue;
      }
      boolean dataBelow = rows.subList(index + 1, rows.size()).stream()
          .anyMatch(row -> row.stream().anyMatch(TextExtractor::looksNumeric));
      if (dataBelow) {
        return index;
      }
    }
    return -1;
  }

  private static boolean looksNumeric(String value) {
    if (value.isBlank()) {
      return false;
    }
    return value.replace(",", "").matches("-?\\d+(?:\\.\\d+)?");
  }

  /** 标题行 + 列名：值，再附原始行，两者都能被检索到；没有表头时保持裸行。 */
  private String xlsxBody(List<List<String>> rows, int header, int index, List<String> cells) {
    StringJoiner raw = new StringJoiner("\t");
    cells.forEach(raw::add);
    if (header < 0) {
      return raw.toString();
    }
    StringBuilder body = new StringBuilder();
    for (int above = 0; above < header; above++) {
      String title = String.join(" ", rows.get(above)).strip();
      if (!title.isBlank()) {
        body.append(title).append('\n');
      }
    }
    List<String> names = rows.get(header);
    StringJoiner pairs = new StringJoiner("；");
    for (int column = 0; column < cells.size(); column++) {
      String value = cells.get(column);
      if (value.isBlank()) {
        continue;
      }
      String name = column < names.size() ? names.get(column) : "";
      pairs.add(name.isBlank() ? "第 " + (column + 1) + " 列：" + value : name + "：" + value);
    }
    body.append("表头上下文（解析器按最近表头补全）：").append(pairs).append('\n');
    body.append("原行：").append(raw);
    return body.toString();
  }

  private void pptx(InputStream content, List<Chunk> chunks) throws IOException {
    try (var show = SlideShowFactory.create(content)) {
      int slideIndex = 0;
      for (Slide<?, ?> slide : show.getSlides()) {
        String location = "第 " + (++slideIndex) + " 张幻灯片";
        StringBuilder text = new StringBuilder();
        int tableIndex = 0;
        for (var shape : slide.getShapes()) {
          if (shape instanceof TextShape<?, ?> textShape && textShape.getText() != null) {
            text.append(textShape.getText()).append('\n');
          } else if (shape instanceof TableShape<?, ?> table) {
            flush(text, location, chunks);
            tableIndex++;
            for (int row = 0; row < table.getNumberOfRows(); row++) {
              StringJoiner cells = new StringJoiner("\t");
              for (int column = 0; column < table.getNumberOfColumns(); column++) {
                var cell = table.getCell(row, column);
                cells.add(cell == null || cell.getText() == null ? "" : cell.getText());
              }
              addTableRow(cells.toString(), locate(location, tableRow(tableIndex, row + 1)), chunks);
            }
          }
        }
        flush(text, location, chunks);
      }
    }
  }

  private void textSections(String text, boolean markdown, String location, List<Chunk> chunks) {
    String section = null;
    StringBuilder paragraphs = new StringBuilder();
    for (String line : text.split("\\R")) {
      Matcher heading = MARKDOWN_HEADING.matcher(line);
      String body = line.strip();
      boolean markdownHeading = markdown && heading.matches();
      if (markdownHeading || POLICY_HEADING.matcher(body).matches()) {
        flush(paragraphs, locate(location, section), chunks);
        section = headingLabel(markdownHeading ? heading.group(1).strip() : body);
      } else if (section == null && location == null && body.isEmpty()) {
        flush(paragraphs, null, chunks);
      }
      paragraphs.append(line).append('\n');
    }
    flush(paragraphs, locate(location, section), chunks);
  }

  private String headingLabel(String text) {
    return text.codePointCount(0, text.length()) <= 160
        ? text : text.substring(0, text.offsetByCodePoints(0, 160)) + "…";
  }

  private String tableRow(int table, int row) {
    return "表 " + table + " 第 " + row + " 行";
  }

  private String locate(String parent, String child) {
    if (parent == null) {
      return child;
    }
    return child == null ? parent : parent + " · " + child;
  }

  private void flush(StringBuilder buffer, String location, List<Chunk> chunks) {
    addChunks(buffer.toString().strip(), location, chunks);
    buffer.setLength(0);
  }

  private void addTableRow(String body, String location, List<Chunk> chunks) {
    if (!body.isBlank()) {
      addChunks(body, location, chunks);
    }
  }

  private void addChunks(String body, String location, List<Chunk> chunks) {
    int start = 0;
    while (start < body.length()) {
      int end = Math.min(start + maxChunkLength, body.length());
      if (end < body.length()) {
        int lineBreak = body.lastIndexOf('\n', end - 1);
        if (lineBreak > start + maxChunkLength / 2) {
          end = lineBreak + 1;
        } else if (Character.isHighSurrogate(body.charAt(end - 1))) {
          end--;
        }
      }
      String chunk = body.substring(start, end);
      if (!chunk.isBlank()) {
        chunks.add(new Chunk(chunk, location));
      }
      if (end >= body.length()) {
        break;
      }
      // 窗口重叠：下一块回退 overlap 个字符，避免条款被窗口边界切断后两侧都读不完整。
      start = Math.max(start + 1, end - chunkOverlap);
    }
  }
}
