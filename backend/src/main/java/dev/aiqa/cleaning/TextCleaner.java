package dev.aiqa.cleaning;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 把原始记录里的格式噪声洗掉：编码层归一、HTML 残留、控制字符、重复标点与多余空白。
 *
 * <p>只做可解释的机械清理，不改写语义；语义质量交给模型分与人工审核。
 */
@Component
public class TextCleaner {

  /** 内联标签与自闭合标签；限制长度避免把整段正文误当标签吞掉。 */
  private static final Pattern TAG = Pattern.compile("<[^>]{1,160}>");
  private static final Pattern SCRIPT_OR_STYLE =
      Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
  private static final Pattern BLOCK_BREAK =
      Pattern.compile("(?i)<br\\s*/?>|</(?:p|div|li|h[1-6]|tr|section|article)>");
  private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
  private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[ \\t\\u3000\\u00A0]+");
  private static final Pattern MANY_NEWLINES = Pattern.compile("\\n{3,}");
  private static final Pattern REPEATED_PUNCT = Pattern.compile("([。！？，、；：,.!?;:])\\1{2,}");
  private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9A-Fa-f]+|[a-zA-Z]+);");

  /**
   * @param text 清理后的正文
   * @param removedTags 被去掉的 HTML 标签数
   * @param removedOddChars 被去掉的控制字符与零宽字符数
   * @param collapsedPunctuation 被合并的重复标点组数
   * @param blankRatio 空白字符占比（0–1），供噪声维度扣分
   */
  public record Cleaned(
      String text, int removedTags, int removedOddChars, int collapsedPunctuation,
      double blankRatio) {}

  /** 清洗一段原始文本；空输入返回空结果，不抛异常。 */
  public Cleaned clean(String raw) {
    if (raw == null || raw.isBlank()) {
      return new Cleaned("", 0, 0, 0, 0);
    }
    String text = Normalizer.normalize(raw, Normalizer.Form.NFC);
    text = SCRIPT_OR_STYLE.matcher(text).replaceAll(" ");

    int removedTags = 0;
    Matcher tagMatcher = TAG.matcher(text);
    StringBuilder withoutTags = new StringBuilder();
    while (tagMatcher.find()) {
      removedTags++;
      tagMatcher.appendReplacement(withoutTags, " ");
    }
    tagMatcher.appendTail(withoutTags);
    text = decodeEntities(withoutTags.toString());

    int removedOddChars = 0;
    Matcher zeroWidth = ZERO_WIDTH.matcher(text);
    while (zeroWidth.find()) {
      removedOddChars++;
    }
    text = zeroWidth.replaceAll("");
    Matcher control = CONTROL.matcher(text);
    while (control.find()) {
      removedOddChars++;
    }
    text = control.replaceAll(" ");

    text = HORIZONTAL_SPACE.matcher(text).replaceAll(" ");
    text = trimLines(text);
    text = MANY_NEWLINES.matcher(text).replaceAll("\n\n");

    int collapsedPunctuation = 0;
    Matcher repeated = REPEATED_PUNCT.matcher(text);
    while (repeated.find()) {
      collapsedPunctuation++;
    }
    text = repeated.replaceAll("$1");
    text = text.strip();

    return new Cleaned(
        text, removedTags, removedOddChars, collapsedPunctuation, blankRatio(text));
  }

  /**
   * 把 HTML 转成纯文本：先去掉脚本样式，块级标签换成换行，再去标签并解码实体。
   * 供清洗与 HTML 数据源读取共用，避免两套去标签逻辑走偏。
   */
  public String stripMarkup(String html) {
    if (html == null || html.isBlank()) {
      return "";
    }
    String text = SCRIPT_OR_STYLE.matcher(html).replaceAll(" ");
    text = BLOCK_BREAK.matcher(text).replaceAll("\n");
    text = TAG.matcher(text).replaceAll(" ");
    text = decodeEntities(text);
    text = HORIZONTAL_SPACE.matcher(text).replaceAll(" ");
    text = trimLines(text);
    return MANY_NEWLINES.matcher(text).replaceAll("\n\n").strip();
  }

  /** 解码常见 HTML 实体与十进制/十六进制数字实体；未知实体保持原样。 */
  public String decodeEntities(String text) {
    Matcher matcher = ENTITY.matcher(text);
    StringBuilder buffer = new StringBuilder();
    while (matcher.find()) {
      String body = matcher.group(1);
      String replacement;
      if (body.startsWith("#x") || body.startsWith("#X")) {
        replacement = fromCodePoint(body.substring(2), 16);
      } else if (body.startsWith("#")) {
        replacement = fromCodePoint(body.substring(1), 10);
      } else {
        replacement = namedEntity(body);
      }
      matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(buffer);
    return buffer.toString();
  }

  private static String fromCodePoint(String digits, int radix) {
    try {
      int codePoint = Integer.parseInt(digits, radix);
      return new String(Character.toChars(codePoint));
    } catch (RuntimeException ex) {
      return "&#" + digits + ";";
    }
  }

  private static String namedEntity(String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "nbsp" -> " ";
      case "amp" -> "&";
      case "lt" -> "<";
      case "gt" -> ">";
      case "quot" -> "\"";
      case "apos" -> "'";
      case "mdash" -> "—";
      case "ndash" -> "–";
      case "hellip" -> "…";
      default -> "&" + name + ";";
    };
  }

  private static String trimLines(String text) {
    String[] lines = text.split("\n", -1);
    StringBuilder buffer = new StringBuilder(text.length());
    for (int index = 0; index < lines.length; index++) {
      buffer.append(lines[index].strip());
      if (index < lines.length - 1) {
        buffer.append('\n');
      }
    }
    return buffer.toString();
  }

  /** 空白占比：空白字符数 / 总字符数，空文本记为 0。 */
  private static double blankRatio(String text) {
    if (text.isEmpty()) {
      return 0;
    }
    long blanks = text.chars().filter(Character::isWhitespace).count();
    return (double) blanks / text.length();
  }
}
