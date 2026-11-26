package dev.aiqa.cleaning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** 压缩包安全边界：路径、条目数、体积与压缩比。 */
class CleaningZipTest {

  private final CleaningZip zip = new CleaningZip();

  @Test
  void extractsSupportedEntriesAndSkipsDirectoriesAndUnknownFormats() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("语料/", new byte[0]);
    entries.put("语料/住宿.csv", "城市,上限\n一线,600\n".getBytes(StandardCharsets.UTF_8));
    entries.put("语料/说明.txt", "差旅标准：一线城市 600 元。".getBytes(StandardCharsets.UTF_8));
    entries.put("语料/banner.png", new byte[] {1, 2, 3});

    CleaningZip.Result result = zip.extract(zipOf(entries));

    assertThat(result.entries()).extracting(CleaningZip.Entry::path)
        .containsExactly("语料/住宿.csv", "语料/说明.txt");
    assertThat(result.skipped()).isEqualTo(2);
  }

  @Test
  void rejectsPathTraversal() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("../evil.txt", "恶意内容".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> zip.extract(zipOf(entries)))
        .isInstanceOf(ZipRejectedException.class)
        .extracting(ex -> ((ZipRejectedException) ex).code())
        .isEqualTo("ARCHIVE_UNSAFE_PATH");
  }

  @Test
  void rejectsEntryAboveSingleFileLimit() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("big.txt", new byte[11 * 1024 * 1024]);

    assertThatThrownBy(() -> zip.extract(zipOf(entries)))
        .isInstanceOf(ZipRejectedException.class)
        .extracting(ex -> ((ZipRejectedException) ex).code())
        .isEqualTo("ARCHIVE_ENTRY_TOO_LARGE");
  }

  @Test
  void rejectsSuspiciousCompressionRatio() throws IOException {
    byte[] repetitive = new byte[2 * 1024 * 1024];
    java.util.Arrays.fill(repetitive, (byte) 'a');
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("bomb.txt", repetitive);

    assertThatThrownBy(() -> zip.extract(zipOf(entries)))
        .isInstanceOf(ZipRejectedException.class)
        .extracting(ex -> ((ZipRejectedException) ex).code())
        .isEqualTo("ARCHIVE_RATIO_ANOMALY");
  }

  @Test
  void normalizesNestedPathsAndRejectsDriveLetters() {
    assertThat(CleaningZip.normalize("语料\\子目录\\住宿.csv")).isEqualTo("语料/子目录/住宿.csv");
    assertThat(CleaningZip.normalize("C:/temp/a.txt")).isNull();
    assertThat(CleaningZip.normalize("/abs/a.txt")).isNull();
    assertThat(CleaningZip.normalize("a/../../b.txt")).isNull();
  }

  private static byte[] zipOf(Map<String, byte[]> entries) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return output.toByteArray();
  }
}
