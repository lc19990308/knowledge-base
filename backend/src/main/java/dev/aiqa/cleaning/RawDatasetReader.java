package dev.aiqa.cleaning;

import dev.aiqa.common.FileNames;
import dev.aiqa.ingest.TextExtractor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 按扩展名把来源文件读成原始记录。
 *
 * <p>文本类文件整体交给后续拆分；表格、JSON、压缩记录逐行/逐条产出，保留行号便于溯源。
 * PDF/DOCX/PPTX 复用已有的 {@link TextExtractor}，不重复实现文档解析。
 */
@Component
public class RawDatasetReader {

  /** 可参与清洗的扩展名；其余在受理阶段计入「已忽略」。 */
  private static final Set<String> SUPPORTED =
      Set.of("txt", "md", "csv", "tsv", "json", "jsonl", "html", "htm", "xlsx", "pdf", "docx",
          "pptx");

  /** 单个文件最多读取的行数/记录数，防止误传超大表格拖垮 worker。 */
  private static final int MAX_RECORDS_PER_FILE = 5000;
  private static final int MAX_JSON_DEPTH = 4;

  private final TextExtractor extractor;
  private final TextCleaner cleaner;
  private final ObjectMapper mapper = new ObjectMapper();

  public RawDatasetReader(TextExtractor extractor, TextCleaner cleaner) {
    this.extractor = extractor;
    this.cleaner = cleaner;
  }

  /** 该扩展名是否参与清洗（大小写不敏感）。 */
  public static boolean isSupported(String extension) {
    return SUPPORTED.contains(extension.toLowerCase(Locale.ROOT));
  }

  /** 按扩展名推断 MIME 类型，与导入链路保持同一套命名。 */
  public static String mediaTypeOf(String extension) {
    return switch (extension.toLowerCase(Locale.ROOT)) {
      case "txt" -> "text/plain";
      case "md" -> "text/markdown";
      case "csv" -> "text/csv";
      case "tsv" -> "text/tab-separated-values";
      case "json" -> "application/json";
      case "jsonl" -> "application/x-ndjson";
      case "html", "htm" -> "text/html";
      case "xlsx" ->
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      case "pdf" -> "application/pdf";
      case "docx" ->
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
      case "pptx" ->
          "application/vnd.openxmlformats-officedocument.presentationml.presentation";
      default -> "application/octet-stream";
    };
  }

  /**
   * 读出一个来源文件的全部原始记录。
   *
   * @param relativePath 保留层级的相对路径，同时作为来源定位与扩展名来源
   * @param content 文件字节
   * @throws RawReadException 编码不支持、内容损坏或不支持该格式时抛出
   */
  public List<RawRecord> read(String relativePath, byte[] content) {
    String extension = FileNames.extension(relativePath);
    if (!isSupported(extension)) {
      throw new RawReadException("UNSUPPORTED_CLEANING_FORMAT");
    }
    return switch (extension.toLowerCase(Locale.ROOT)) {
      case "txt", "md" -> List.of(new RawRecord(decodeUtf8(content), relativePath));
      case "html", "htm" -> List.of(new RawRecord(stripHtml(content), relativePath));
      case "csv" -> delimited(relativePath, decodeUtf8(content), ',');
      case "tsv" -> delimited(relativePath, decodeUtf8(content), '\t');
      case "json" -> json(relativePath, decodeUtf8(content));
      case "jsonl" -> jsonLines(relativePath, decodeUtf8(content));
      case "xlsx" -> spreadsheet(relativePath, content);
      default -> extracted(relativePath, content);
    };
  }

  /** 严格 UTF-8 解码：不做猜测式转码，解码失败明确报错并提示另存为 UTF-8。 */
  private static String decodeUtf8(byte[] content) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      String text = decoder.decode(ByteBuffer.wrap(content)).toString();
      return text.startsWith("\uFEFF") ? text.substring(1) : text;
    } catch (CharacterCodingException ex) {
      throw new RawReadException("ENCODING_UNSUPPORTED", "文件不是 UTF-8 编码");
    }
  }

  private static List<RawRecord> delimited(String path, String text, char delimiter) {
    List<List<String>> rows = parseDelimited(text, delimiter);
    List<RawRecord> records = new ArrayList<>();
    if (rows.isEmpty()) {
      return records;
    }
    List<String> header = rows.get(0);
    for (int index = 1; index < rows.size() && records.size() < MAX_RECORDS_PER_FILE; index++) {
      List<String> row = rows.get(index);
      if (row.stream().allMatch(String::isBlank)) {
        continue;
      }
      StringBuilder body = new StringBuilder();
      for (int column = 0; column < row.size(); column++) {
        String value = row.get(column).strip();
        if (value.isEmpty()) {
          continue;
        }
        if (column < header.size() && !header.get(column).isBlank()) {
          body.append(header.get(column).strip()).append('：').append(value).append('\n');
        } else {
          body.append(value).append('\n');
        }
      }
      String text2 = body.toString().strip();
      if (!text2.isEmpty()) {
        records.add(new RawRecord(text2, path + ":" + (index + 1)));
      }
    }
    return records;
  }

  /** 简易 RFC4180 解析：支持引号包裹、转义引号与字段内换行。 */
  static List<List<String>> parseDelimited(String text, char delimiter) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < text.length(); index++) {
      char ch = text.charAt(index);
      if (quoted) {
        if (ch == '"') {
          if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
            field.append('"');
            index++;
          } else {
            quoted = false;
          }
        } else {
          field.append(ch);
        }
      } else if (ch == '"' && field.toString().isBlank()) {
        quoted = true;
        field.setLength(0);
      } else if (ch == delimiter) {
        row.add(field.toString());
        field.setLength(0);
      } else if (ch == '\n') {
        row.add(field.toString());
        field.setLength(0);
        rows.add(row);
        row = new ArrayList<>();
      } else if (ch != '\r') {
        field.append(ch);
      }
    }
    if (field.length() > 0 || !row.isEmpty()) {
      row.add(field.toString());
      rows.add(row);
    }
    return rows;
  }

  private List<RawRecord> json(String path, String text) {
    JsonNode root;
    try {
      root = mapper.readTree(text);
    } catch (RuntimeException ex) {
      throw new RawReadException("INVALID_JSON", "JSON 解析失败");
    }
    List<RawRecord> records = new ArrayList<>();
    if (root.isArray()) {
      int index = 0;
      for (JsonNode element : root.values()) {
        if (records.size() >= MAX_RECORDS_PER_FILE) {
          break;
        }
        String body = flatten(element);
        if (!body.isBlank()) {
          records.add(new RawRecord(body, path + "#" + (++index)));
        }
      }
      return records;
    }
    String body = flatten(root);
    if (!body.isBlank()) {
      records.add(new RawRecord(body, path));
    }
    return records;
  }

  private List<RawRecord> jsonLines(String path, String text) {
    List<RawRecord> records = new ArrayList<>();
    String[] lines = text.split("\\R");
    for (int index = 0; index < lines.length && records.size() < MAX_RECORDS_PER_FILE; index++) {
      String line = lines[index].strip();
      if (line.isEmpty()) {
        continue;
      }
      try {
        String body = flatten(mapper.readTree(line));
        if (!body.isBlank()) {
          records.add(new RawRecord(body, path + ":" + (index + 1)));
        }
      } catch (RuntimeException ex) {
        throw new RawReadException("INVALID_JSON", "JSONL 第 " + (index + 1) + " 行解析失败");
      }
    }
    return records;
  }

  /** 把 JSON 值压成「字段：值」的多行文本；嵌套对象用点号、数组用下标表示层级。 */
  private String flatten(JsonNode node) {
    List<String> lines = new ArrayList<>();
    flattenInto("", node, lines, 0);
    return String.join("\n", lines);
  }

  private void flattenInto(String prefix, JsonNode node, List<String> lines, int depth) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return;
    }
    if (node.isObject()) {
      if (depth >= MAX_JSON_DEPTH) {
        lines.add(label(prefix) + node.toString());
        return;
      }
      for (Map.Entry<String, JsonNode> entry : node.properties()) {
        String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
        flattenInto(key, entry.getValue(), lines, depth + 1);
      }
      return;
    }
    if (node.isArray()) {
      List<String> scalars = new ArrayList<>();
      boolean nested = false;
      for (JsonNode element : node.values()) {
        if (element.isObject() || element.isArray()) {
          nested = true;
          break;
        }
        scalars.add(element.asText(""));
      }
      if (!nested) {
        lines.add(label(prefix) + String.join("、", scalars));
        return;
      }
      int index = 1;
      for (JsonNode element : node.values()) {
        flattenInto(prefix + "[" + index++ + "]", element, lines, depth + 1);
      }
      return;
    }
    lines.add(label(prefix) + node.asText(""));
  }

  private static String label(String prefix) {
    return prefix.isEmpty() ? "" : prefix + "：";
  }

  private List<RawRecord> spreadsheet(String path, byte[] content) {
    List<RawRecord> records = new ArrayList<>();
    DataFormatter formatter = new DataFormatter(Locale.CHINA);
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
      for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        List<String> header = new ArrayList<>();
        if (headerRow != null) {
          for (int column = 0; column < headerRow.getLastCellNum(); column++) {
            header.add(cellText(headerRow.getCell(column), formatter));
          }
        }
        for (int rowIndex = sheet.getFirstRowNum() + 1;
            rowIndex <= sheet.getLastRowNum() && records.size() < MAX_RECORDS_PER_FILE;
            rowIndex++) {
          Row row = sheet.getRow(rowIndex);
          if (row == null) {
            continue;
          }
          StringBuilder body = new StringBuilder();
          for (int column = 0; column < row.getLastCellNum(); column++) {
            String value = cellText(row.getCell(column), formatter).strip();
            if (value.isEmpty()) {
              continue;
            }
            if (column < header.size() && !header.get(column).isBlank()) {
              body.append(header.get(column).strip()).append('：').append(value).append('\n');
            } else {
              body.append(value).append('\n');
            }
          }
          String text = body.toString().strip();
          if (!text.isEmpty()) {
            records.add(
                new RawRecord(
                    text,
                    path + "#" + sheet.getSheetName() + ":" + (rowIndex + 1)));
          }
        }
      }
    } catch (IOException | RuntimeException ex) {
      throw new RawReadException("EXTRACT_FAILED", "表格读取失败");
    }
    return records;
  }

  private static String cellText(Cell cell, DataFormatter formatter) {
    return cell == null ? "" : formatter.formatCellValue(cell).strip();
  }

  private List<RawRecord> extracted(String path, byte[] content) {
    TextExtractor.Extracted extracted;
    try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
      extracted = extractor.extract(path, input);
    } catch (IOException | RuntimeException ex) {
      throw new RawReadException("EXTRACT_FAILED", "文档解析失败");
    }
    if (extracted.chunks().isEmpty()) {
      throw new RawReadException("NO_EXTRACTABLE_TEXT", "没有可提取的正文");
    }
    List<RawRecord> records = new ArrayList<>();
    for (TextExtractor.Chunk chunk : extracted.chunks()) {
      records.add(new RawRecord(chunk.body(), locate(path, chunk.sourceLocation())));
    }
    return records;
  }

  private static String locate(String path, String suffix) {
    return suffix == null || suffix.isBlank() ? path : path + " · " + suffix;
  }

  private String stripHtml(byte[] content) {
    return cleaner.stripMarkup(decodeUtf8(content));
  }
}
