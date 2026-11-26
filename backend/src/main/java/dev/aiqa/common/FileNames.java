package dev.aiqa.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

public final class FileNames {

  private static final Set<String> SUPPORTED =
      Set.of("pdf", "docx", "xlsx", "pptx", "txt", "md");

  private FileNames() {}

  /** NFC, no path components, trimmed, locale-independent lower case. */
  public static String normalize(String submitted) {
    if (submitted == null) {
      throw ApiException.invalid("文件名不能为空");
    }
    String name = Normalizer.normalize(submitted, Normalizer.Form.NFC).strip();
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    if (slash >= 0) {
      throw ApiException.invalid("文件名不能包含路径");
    }
    if (name.isEmpty() || name.startsWith(".") || name.length() > 255) {
      throw ApiException.invalid("文件名必须为 1–255 个字符且不能为隐藏文件");
    }
    if (name.chars().anyMatch(Character::isISOControl)) {
      throw ApiException.invalid("文件名包含控制字符");
    }
    if (!SUPPORTED.contains(extension(name))) {
      throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
          "不支持的文档格式");
    }
    return name.toLowerCase(Locale.ROOT);
  }

  public static String extension(String name) {
    int dot = name.lastIndexOf('.');
    return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  public static String displayName(String submitted) {
    String name = Normalizer.normalize(submitted, Normalizer.Form.NFC).strip();
    return name.length() > 255 ? name.substring(0, 255) : name;
  }
}
