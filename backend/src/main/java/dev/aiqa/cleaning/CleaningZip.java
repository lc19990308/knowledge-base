package dev.aiqa.cleaning;

import dev.aiqa.common.FileNames;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

/**
 * 安全解包：只解 .zip，拒绝路径穿越、超量条目、超大单文件、解压总量超限与异常压缩比。
 *
 * <p>先落到临时文件再用 {@link ZipFile} 读取：流式 {@code ZipInputStream} 在读取前拿不到可靠的
 * 原始大小与压缩后大小，无法做压缩比判定；中心目录里这两个值是可用的。
 * 任一限制被触发都拒绝整个压缩包，避免「部分解出」留下半套数据。临时文件在结束时删除。
 */
@Component
public class CleaningZip {

  /** 压缩包内条目总数上限（含目录与不支持的格式）。 */
  static final int MAX_ENTRIES = 2000;
  /** 单个条目解压后上限，与单文件上传限制一致。 */
  static final long MAX_ENTRY_BYTES = 10L * 1024 * 1024;
  /** 全部条目解压后总量上限，与整批上传限制一致。 */
  static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;
  /** 压缩比保护：超过 100 倍且解压后超过 1 MB 视为可疑压缩炸弹。 */
  static final long MAX_RATIO = 100;
  static final long MAX_RATIO_MIN_BYTES = 1024 * 1024;
  private static final int MAX_PATH_LENGTH = 512;

  /** @param entries 受支持且已安全解出的条目 @param skipped 目录项与不支持格式的条目数 */
  public record Result(List<Entry> entries, int skipped) {}

  /** @param path 归一化后的包内相对路径 @param content 解压后的字节 */
  public record Entry(String path, byte[] content) {}

  /** 解出所有可清洗条目；不安全或超限时抛 {@link ZipRejectedException}。 */
  public Result extract(byte[] archive) {
    Path temporary = null;
    try {
      temporary = Files.createTempFile("aiqa-cleaning-", ".zip");
      Files.write(temporary, archive);
      return read(new ZipFile(temporary.toFile(), StandardCharsets.UTF_8));
    } catch (ZipRejectedException ex) {
      throw ex;
    } catch (IOException ex) {
      // 加密条目、损坏的中头或截断的包都读不下去；统一按不可读拒绝，避免静默跳过。
      throw new ZipRejectedException("ARCHIVE_UNREADABLE");
    } finally {
      deleteQuietly(temporary);
    }
  }

  private Result read(ZipFile zip) {
    List<Entry> entries = new ArrayList<>();
    int skipped = 0;
    int totalEntries = 0;
    long totalBytes = 0;
    try (zip) {
      Enumeration<? extends ZipEntry> iterator = zip.entries();
      while (iterator.hasMoreElements()) {
        ZipEntry entry = iterator.nextElement();
        totalEntries++;
        if (totalEntries > MAX_ENTRIES) {
          throw new ZipRejectedException("ARCHIVE_TOO_MANY_ENTRIES");
        }
        if (entry.isDirectory()) {
          skipped++;
          continue;
        }
        String path = normalize(entry.getName());
        if (path == null) {
          throw new ZipRejectedException("ARCHIVE_UNSAFE_PATH");
        }
        if (!RawDatasetReader.isSupported(FileNames.extension(path))) {
          skipped++;
          continue;
        }
        long declared = entry.getSize();
        long compressed = entry.getCompressedSize();
        if (declared > MAX_ENTRY_BYTES) {
          throw new ZipRejectedException("ARCHIVE_ENTRY_TOO_LARGE");
        }
        if (declared > MAX_RATIO_MIN_BYTES
            && compressed > 0
            && declared / compressed > MAX_RATIO) {
          throw new ZipRejectedException("ARCHIVE_RATIO_ANOMALY");
        }
        byte[] content = readLimited(zip.getInputStream(entry));
        totalBytes += content.length;
        if (totalBytes > MAX_TOTAL_BYTES) {
          throw new ZipRejectedException("ARCHIVE_TOO_LARGE");
        }
        entries.add(new Entry(path, content));
      }
    } catch (IOException ex) {
      throw new ZipRejectedException("ARCHIVE_UNREADABLE");
    }
    return new Result(List.copyOf(entries), skipped);
  }

  /** 把包内路径归一化；包含盘符、绝对路径或 `..` 时返回 null。 */
  static String normalize(String rawName) {
    if (rawName == null) {
      return null;
    }
    String name = rawName.replace('\\', '/').strip();
    if (name.isEmpty() || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
      return null;
    }
    if (name.chars().anyMatch(ch -> ch < 0x20)) {
      return null;
    }
    Deque<String> parts = new ArrayDeque<>();
    for (String part : name.split("/")) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        return null;
      }
      parts.addLast(part);
    }
    if (parts.isEmpty()) {
      return null;
    }
    String joined = String.join("/", parts);
    return joined.length() > MAX_PATH_LENGTH ? null : joined;
  }

  /** 读取当前条目，超过单条目上限立即拒绝。 */
  private static byte[] readLimited(InputStream input) throws IOException {
    byte[] buffer = new byte[8192];
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    try (input) {
      int read;
      while ((read = input.read(buffer)) != -1) {
        output.write(buffer, 0, read);
        if (output.size() > MAX_ENTRY_BYTES) {
          throw new ZipRejectedException("ARCHIVE_ENTRY_TOO_LARGE");
        }
      }
    }
    return output.toByteArray();
  }

  private static void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // 临时文件删除失败不影响本次结果；系统临时目录会自行清理。
    }
  }
}
