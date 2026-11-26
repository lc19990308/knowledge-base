package dev.aiqa.cleaning;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 把原始记录拆成可入库的条目：合并过短的块、按句拆分超长块、提炼标题。
 *
 * <p>拆分以「可独立理解的一段」为单位，不再往下切碎，避免把一条制度拆成半句话。
 */
@Component
public class ItemSplitter {

  /** 正文下限：比它短的段落视为噪声或残句。 */
  static final int MIN_BODY = 20;
  /** 正文上限：与文档切块一致，超长按句子边界再拆。 */
  static final int MAX_BODY = 4000;
  static final int TITLE_MAX = 240;
  static final int RAW_EXCERPT_MAX = 2000;

  private static final String[] SENTENCE_SEPARATORS = {"。", "！", "？", "；", "\n"};

  private final TextCleaner cleaner;

  public ItemSplitter(TextCleaner cleaner) {
    this.cleaner = cleaner;
  }

  /** @param title 条目标题 @param body 清洗后正文 @param rawExcerpt 清洗前摘录 @param location 来源位置 */
  public record Candidate(String title, String body, String rawExcerpt, String location) {}

  /** 把一条原始记录拆成若干条目；清洗后不足下限的块直接丢弃。 */
  public List<Candidate> split(RawRecord record) {
    List<Candidate> candidates = new ArrayList<>();
    for (String block : mergeShortBlocks(blocks(record.text()))) {
      TextCleaner.Cleaned cleaned = cleaner.clean(block);
      String body = cleaned.text();
      if (body.codePointCount(0, body.length()) < MIN_BODY) {
        continue;
      }
      String excerpt = truncate(block.strip(), RAW_EXCERPT_MAX);
      for (String part : splitLongBlock(body)) {
        candidates.add(
            new Candidate(titleOf(part), part, excerpt, record.location()));
      }
    }
    return candidates;
  }

  /** 按空行切块；没有空行的文本按单行处理。 */
  private static List<String> blocks(String text) {
    List<String> blocks = new ArrayList<>();
    for (String block : text.split("\\n\\s*\\n")) {
      String stripped = block.strip();
      if (!stripped.isEmpty()) {
        blocks.add(stripped);
      }
    }
    return blocks;
  }

  /** 过短块并入前一块；开头就过短的块留给下一块，避免单独成为一条无意义条目。 */
  private static List<String> mergeShortBlocks(List<String> blocks) {
    List<String> merged = new ArrayList<>();
    StringBuilder pending = new StringBuilder();
    for (String block : blocks) {
      String candidate = pending.isEmpty() ? block : pending + "\n" + block;
      pending.setLength(0);
      if (candidate.codePointCount(0, candidate.length()) < MIN_BODY) {
        pending.append(candidate);
        continue;
      }
      if (!merged.isEmpty()) {
        String last = merged.get(merged.size() - 1);
        if (last.codePointCount(0, last.length()) < MIN_BODY
            && candidate.codePointCount(0, candidate.length())
                + last.codePointCount(0, last.length())
                <= MAX_BODY) {
          merged.set(merged.size() - 1, last + "\n" + candidate);
          continue;
        }
      }
      merged.add(candidate);
    }
    if (!pending.isEmpty()) {
      if (!merged.isEmpty()) {
        merged.set(merged.size() - 1, merged.get(merged.size() - 1) + "\n" + pending);
      } else {
        merged.add(pending.toString());
      }
    }
    return merged;
  }

  /** 超过上限的块按句末标点继续拆，保证每段不超过 MAX_BODY。 */
  private static List<String> splitLongBlock(String body) {
    List<String> parts = new ArrayList<>();
    int start = 0;
    while (start < body.length()) {
      int end = Math.min(start + MAX_BODY, body.length());
      if (end < body.length()) {
        int boundary = lastBoundary(body, start, end);
        if (boundary > start) {
          end = boundary;
        } else if (Character.isHighSurrogate(body.charAt(end - 1))) {
          end--;
        }
      }
      String part = body.substring(start, end).strip();
      if (!part.isEmpty()) {
        parts.add(part);
      }
      start = end;
    }
    return parts;
  }

  private static int lastBoundary(String body, int start, int end) {
    int best = -1;
    for (String separator : SENTENCE_SEPARATORS) {
      int index = body.lastIndexOf(separator, end - 1);
      if (index >= start + MAX_BODY / 2) {
        best = Math.max(best, index + separator.length());
      }
    }
    return best;
  }

  /** 标题取首行；首行过短或带列表符号时退化为正文开头并去掉标记。 */
  static String titleOf(String body) {
    String firstLine = body.lines().findFirst().orElse(body).strip();
    String title = stripMarkers(firstLine);
    if (title.codePointCount(0, title.length()) < 4) {
      title = stripMarkers(body);
    }
    return truncate(title.replaceAll("\\s+", " "), TITLE_MAX);
  }

  private static String stripMarkers(String text) {
    return text.replaceFirst("^\\s*(#{1,6}|[-*•·]|\\d+[.、)]|[一二三四五六七八九十]+[、.])\\s*", "");
  }

  /** 按码点截断，避免把代理对切成半个字符。 */
  static String truncate(String text, int max) {
    if (text.codePointCount(0, text.length()) <= max) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, max)) + "…";
  }
}
