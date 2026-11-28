package dev.aiqa.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiqa.ingest.TextExtractor.Chunk;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class TextExtractorTest {
  private final TextExtractor extractor = new TextExtractor();

  @Test
  void markdownKeepsOriginalHeadingsAndSeparatesSections() throws IOException {
    String text = "引言\n\n# 请假政策\n第一段\n\n第二段\n## 审批 ###\n审批内容\n";
    assertThat(extractor.extract("政策.md", bytes(text)).chunks()).containsExactly(
        new Chunk("引言", null),
        new Chunk("# 请假政策\n第一段\n\n第二段", "请假政策"),
        new Chunk("## 审批 ###\n审批内容", "审批"));
  }

  @Test
  void plainTextRecognizesPolicyArticlesAndSplitsLongSectionsIntoOverlappingWindows()
      throws IOException {
    String first = "第一条 ";
    String text = first + "甲".repeat(1599 - first.length());
    var chunks = extractor.extract("制度.txt", bytes(text + "\n第二条 其他规定")).chunks();
    assertThat(chunks.size()).isGreaterThan(2);
    assertThat(chunks)
        .allSatisfy(chunk -> assertThat(chunk.body().length()).isLessThanOrEqualTo(800));
    // 相邻窗口有 120 字重叠：长条款在边界处不会两边都缺半句。
    assertThat(chunks.get(1).body()).startsWith(chunks.get(0).body().substring(680));
    assertThat(chunks.get(1).sourceLocation()).isEqualTo(chunks.get(0).sourceLocation());
    assertThat(chunks.get(chunks.size() - 1))
        .isEqualTo(new Chunk("第二条 其他规定", "第二条 其他规定"));
  }

  @Test
  void splitDoesNotBreakSurrogatePairs() throws IOException {
    String text = "甲".repeat(799) + "😀" + "乙".repeat(200);
    var chunks = extractor.extract("制度.txt", bytes(text)).chunks();
    assertThat(chunks)
        .allSatisfy(
            chunk -> {
              String body = chunk.body();
              assertThat(Character.isHighSurrogate(body.charAt(body.length() - 1))).isFalse();
              assertThat(Character.isLowSurrogate(body.charAt(0))).isFalse();
            });
  }

  @Test
  void pdfUsesPhysicalPageNumbersEvenWhenPagesAreBlank() throws IOException {
    try (PDDocument document = new PDDocument(); var out = new ByteArrayOutputStream()) {
      addPage(document, "Travel allowance");
      document.addPage(new PDPage());
      addPage(document, "Approval process");
      document.save(out);
      assertThat(extractor.extract("policy.pdf", new ByteArrayInputStream(out.toByteArray())).chunks())
          .containsExactly(new Chunk("Travel allowance", "第 1 页"),
              new Chunk("Approval process", "第 3 页"));
    }
  }

  @Test
  void wordKeepsHeadingAndTableRowsInBodyOrder() throws IOException {
    try (XWPFDocument document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
      var heading = document.createParagraph();
      heading.setStyle("Heading1");
      heading.createRun().setText("Travel policy");
      document.createParagraph().createRun().setText("Allowances are listed below.");
      var table = document.createTable(2, 2);
      table.getRow(0).getCell(0).setText("City");
      table.getRow(0).getCell(1).setText("Maximum");
      table.getRow(1).getCell(0).setText("Shanghai");
      table.getRow(1).getCell(1).setText("300");
      document.createParagraph().createRun().setText("Submit receipts.");
      document.write(out);
      assertThat(extractor.extract("policy.docx", new ByteArrayInputStream(out.toByteArray())).chunks())
          .containsExactly(new Chunk("Travel policy\nAllowances are listed below.", "Travel policy"),
              new Chunk("City\tMaximum", "Travel policy · 表 1 第 1 行"),
              new Chunk("Shanghai\t300", "Travel policy · 表 1 第 2 行"),
              new Chunk("Submit receipts.", "Travel policy"));
    }
  }

  @Test
  void workbookCarriesSheetTitleAndHeaderIntoEachDataRow() throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("住宿限额");
      sheet.createRow(0).createCell(0).setCellValue("住宿限额｜境内每人每晚");
      var header = sheet.createRow(1);
      header.createCell(0).setCellValue("城市");
      header.createCell(1).setCellValue("档位");
      header.createCell(2).setCellValue("上限（元）");
      var data = sheet.createRow(2);
      data.createCell(0).setCellValue("杭州");
      data.createCell(1).setCellValue("A 档");
      data.createCell(2).setCellValue(620);
      workbook.write(out);

      var chunks = extractor.extract("rates.xlsx", new ByteArrayInputStream(out.toByteArray()))
          .chunks();
      assertThat(chunks).hasSize(1);
      assertThat(chunks.get(0).sourceLocation()).isEqualTo("工作表「住宿限额」第 3 行");
      // 数据行离开表头就看不出 620 是什么；解析器补全的上下文必须带单位。
      assertThat(chunks.get(0).body())
          .contains("住宿限额｜境内每人每晚")
          .contains("上限（元）：620")
          .contains("城市：杭州")
          .contains("原行：杭州\tA 档\t620");
    }
  }

  @Test
  void workbookWithoutHeaderKeepsRawRows() throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var rates = workbook.createSheet("补贴标准");
      var row = rates.createRow(3);
      row.createCell(1).setCellValue("上海");
      row.createCell(3).setCellValue(300);
      workbook.createSheet("说明").createRow(0).createCell(0).setCellValue("以票据为准");
      workbook.write(out);
      assertThat(extractor.extract("rates.xlsx", new ByteArrayInputStream(out.toByteArray())).chunks())
          .containsExactly(new Chunk("\t上海\t\t300", "工作表「补贴标准」第 4 行"),
              new Chunk("以票据为准", "工作表「说明」第 1 行"));
    }
  }

  @Test
  void slidesKeepTheirNumbersAndTableRows() throws IOException {
    try (XMLSlideShow show = new XMLSlideShow(); var out = new ByteArrayOutputStream()) {
      var slide = show.createSlide();
      slide.createTextBox().setText("Expenses");
      var table = slide.createTable(1, 2);
      table.getCell(0, 0).setText("Rail");
      table.getCell(0, 1).setText("Second class");
      show.createSlide().createTextBox().setText("Receipts");
      show.write(out);
      assertThat(extractor.extract("policy.pptx", new ByteArrayInputStream(out.toByteArray())).chunks())
          .containsExactly(new Chunk("Expenses", "第 1 张幻灯片"),
              new Chunk("Rail\tSecond class", "第 1 张幻灯片 · 表 1 第 1 行"),
              new Chunk("Receipts", "第 2 张幻灯片"));
    }
  }

  private ByteArrayInputStream bytes(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }

  private void addPage(PDDocument document, String body) throws IOException {
    PDPage page = new PDPage();
    document.addPage(page);
    try (var content = new PDPageContentStream(document, page)) {
      content.beginText();
      content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
      content.newLineAtOffset(50, 700);
      content.showText(body);
      content.endText();
    }
  }
}
