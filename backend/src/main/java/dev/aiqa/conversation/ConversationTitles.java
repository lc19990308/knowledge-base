package dev.aiqa.conversation;

import java.util.List;

/**
 * 会话标题提炼：从首个问题（必要时结合首轮回答的第一句）生成主题，超长按标点回退截断并加省略号。
 *
 * <p>标题只描述主题，不复述完整问题；去掉「请问」「帮我」等客套前缀，避免侧栏里出现无信息量的开头。
 */
public final class ConversationTitles {

  /** 标题最大码点长度（含省略号）。侧栏单行约 15 个汉字，标题最多占两行。 */
  public static final int MAX_CODE_POINTS = 30;

  /** 问题过短（如「怎么办」）时，用首轮回答的第一句补充可读主题。 */
  static final int MIN_QUESTION_CODE_POINTS = 6;

  private static final int BOUNDARY_LOOKBACK = 12;
  private static final String ELLIPSIS = "…";
  private static final String FALLBACK_TITLE = "新会话";

  private static final List<String> LEADING_FILLERS =
      List.of(
          "你好", "您好", "请问一下", "请问", "请教一下", "请教",
          "麻烦问一下", "麻烦", "帮我", "帮忙",
          "我想了解一下", "我想了解", "我想知道", "我想问一下", "我想问",
          "想问一下", "问一下");

  private static final List<String> ANSWER_PREFIXES =
      List.of(
          "根据当前可访问且已发布的资料：", "根据当前可访问且已发布的资料:",
          "根据已发布资料：", "根据已发布资料:",
          "根据资料：", "根据资料:");

  private static final String LEADING_PUNCTUATION = "^[\\s,，、:：;；。.!！?？]+";
  private static final String TRAILING_PUNCTUATION = "[\\s,，、:：;；。.!！?？]+$";
  /** 引用编号不进标题；模型常写成 [1] 或【1】。 */
  private static final String CITATION = "\\[\\d{1,2}\\]|【\\d{1,2}】";
  /** 回答开头常见的「根据资料，」「根据《员工手册》规定，」等出处交代。 */
  private static final String ANSWER_LEAD = "^根据[^。！？；\\n]{0,30}?[，,：:]\\s*";

  private ConversationTitles() {}

  /** 会话创建时的标题：只依赖问题本身，回答失败也仍有可读主题。 */
  public static String fromQuestion(String question) {
    String title = core(question);
    return title.isEmpty() ? FALLBACK_TITLE : truncate(title);
  }

  /** 首轮回答完成后的标题：问题过短时改用回答第一句补全主题。 */
  public static String fromExchange(String question, String answer) {
    String title = core(question);
    if (title.codePointCount(0, title.length()) < MIN_QUESTION_CODE_POINTS) {
      String context = firstSentence(answer);
      if (!context.isEmpty()) {
        title = context;
      }
    }
    return title.isEmpty() ? FALLBACK_TITLE : truncate(title);
  }

  /** 去客套前缀、压缩空白、去掉首尾标点。 */
  static String core(String question) {
    if (question == null) {
      return "";
    }
    String text = question.replaceAll("\\s+", " ").strip();
    text = text.replaceFirst(LEADING_PUNCTUATION, "").strip();
    boolean changed = true;
    while (changed && !text.isEmpty()) {
      changed = false;
      for (String filler : LEADING_FILLERS) {
        if (text.startsWith(filler)) {
          text = text.substring(filler.length()).replaceFirst(LEADING_PUNCTUATION, "").strip();
          changed = true;
          break;
        }
      }
    }
    return stripNoise(text);
  }

  /** 去引用编号、首尾标点并压缩空白，标题不保留模型的行文噪声。 */
  private static String stripNoise(String value) {
    return value
        .replaceAll(CITATION, "")
        .replaceAll("\\s+", " ")
        .replaceFirst(LEADING_PUNCTUATION, "")
        .replaceFirst(TRAILING_PUNCTUATION, "")
        .strip();
  }

  /** 回答的第一句；去掉假模型与常见模板的开场白，避免标题变成「根据资料…」。 */
  private static String firstSentence(String answer) {
    if (answer == null) {
      return "";
    }
    String text = answer.replaceAll("\\s+", " ").strip();
    for (String prefix : ANSWER_PREFIXES) {
      if (text.startsWith(prefix)) {
        text = text.substring(prefix.length()).strip();
        break;
      }
    }
    text = text.replaceFirst(ANSWER_LEAD, "").replaceAll(CITATION, "").strip();
    int end = text.length();
    for (int index = 0; index < text.length(); index++) {
      char ch = text.charAt(index);
      if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == ';' || ch == '.') {
        end = index;
        break;
      }
    }
    return stripNoise(text.substring(0, end));
  }

  /**
   * 超过 {@link #MAX_CODE_POINTS} 时从最近的标点处截断并补省略号；避免把英文单词从中间切开。
   */
  static String truncate(String text) {
    int[] codePoints = text.codePoints().toArray();
    if (codePoints.length <= MAX_CODE_POINTS) {
      return text;
    }
    int cut = MAX_CODE_POINTS - 1;
    int lowest = Math.max(1, cut - BOUNDARY_LOOKBACK);
    for (int index = cut; index > lowest; index--) {
      if (isBoundary(codePoints[index])) {
        cut = index;
        break;
      }
      if (isBoundary(codePoints[index - 1])) {
        cut = index - 1;
        break;
      }
    }
    while (cut > 1 && isAsciiWord(codePoints[cut - 1]) && isAsciiWord(codePoints[cut])) {
      cut--;
    }
    return new String(codePoints, 0, cut).stripTrailing() + ELLIPSIS;
  }

  private static boolean isBoundary(int codePoint) {
    return Character.isWhitespace(codePoint)
        || "，,、；;：:。.！!？?".indexOf(codePoint) >= 0;
  }

  private static boolean isAsciiWord(int codePoint) {
    return (codePoint >= '0' && codePoint <= '9')
        || (codePoint >= 'a' && codePoint <= 'z')
        || (codePoint >= 'A' && codePoint <= 'Z');
  }
}
