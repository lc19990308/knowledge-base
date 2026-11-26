package dev.aiqa.ingest;

import dev.aiqa.common.FileNames;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Development content check. It rejects files whose bytes contradict their extension and obvious
 * script payloads; a production deployment replaces this with an approved anti-malware scanner.
 */
@Component
public class FileScanner {

  public enum Verdict {
    CLEAN,
    REJECTED
  }

  public record Result(Verdict verdict, String code) {
    public static final Result CLEAN = new Result(Verdict.CLEAN, null);
    public static final Result REJECTED = new Result(Verdict.REJECTED, "SUSPICIOUS_FILE");
  }

  private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md");
  private static final byte[] ZIP_MAGIC = {0x50, 0x4b, 0x03, 0x04};
  private static final byte[] EICAR =
      "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
          .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  public Result scan(String filename, byte[] prefix) {
    String extension = FileNames.extension(filename).toLowerCase(Locale.ROOT);
    if (prefix.length == 0) {
      return Result.REJECTED;
    }
    if (contains(prefix, EICAR)) {
      return Result.REJECTED;
    }
    if ("pdf".equals(extension)) {
      return startsWith(prefix, "%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
          ? Result.CLEAN
          : Result.REJECTED;
    }
    if (Set.of("docx", "xlsx", "pptx").contains(extension)) {
      return startsWith(prefix, ZIP_MAGIC) ? Result.CLEAN : Result.REJECTED;
    }
    if (TEXT_EXTENSIONS.contains(extension)) {
      return looksBinary(prefix) ? Result.REJECTED : Result.CLEAN;
    }
    return Result.REJECTED;
  }

  private static boolean startsWith(byte[] value, byte[] magic) {
    if (value.length < magic.length) {
      return false;
    }
    for (int index = 0; index < magic.length; index++) {
      if (value[index] != magic[index]) {
        return false;
      }
    }
    return true;
  }

  private static boolean contains(byte[] haystack, byte[] needle) {
    outer:
    for (int start = 0; start + needle.length <= haystack.length; start++) {
      for (int offset = 0; offset < needle.length; offset++) {
        if (haystack[start + offset] != needle[offset]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  private static boolean looksBinary(byte[] prefix) {
    for (byte value : prefix) {
      if (value == 0) {
        return true;
      }
    }
    return false;
  }
}
