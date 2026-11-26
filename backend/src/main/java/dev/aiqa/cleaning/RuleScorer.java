package dev.aiqa.cleaning;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 规则打分：五个维度各 20 分，合计 0–100，明细随条目一起落库供审核核对。
 *
 * <p>只依赖清洗后的文本与清洗时的噪声计数，不访问网络，便于离线复算与单测。
 */
@Component
public class RuleScorer {

  /** 每条规则的满分；总分等于五项之和。 */
  static final int DIMENSION_MAX = 20;

  private static final int MIN_BODY = 20;
  private static final int COMFORTABLE_MIN = 80;
  private static final int COMFORTABLE_MAX = 1200;
  private static final int MAX_BODY = 4000;

  private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?；;：:.]$");
  private static final Pattern STRUCTURE_WORD =
      Pattern.compile("(适用|条件|标准|步骤|规定|流程|申请|期限|报销|审批|须知|要求)");
  private static final Pattern DATE = Pattern.compile(
      "(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?|\\d{1,2}月\\d{1,2}日)");
  private static final Pattern AMOUNT = Pattern.compile("(\\d+(\\.\\d+)?\\s*(元|万元|%|％|天|个工作日|工作日|小时))");
  private static final Pattern DEADLINE = Pattern.compile("(期限|截至|之内|日内|天内|个工作日内|有效期)");
  private static final Pattern LATIN = Pattern.compile("[A-Za-z]{3,}");
  private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*([-*•·]|\\d+[.、)]|[一二三四五六七八九十]+[、.])\\s*\\S");
  private static final Pattern FIELD_LINE = Pattern.compile("^[^：:\\n]{1,24}[：:]\\S");
  private static final Pattern QA_MARKER = Pattern.compile("(问[：:]|答[：:]|Q[:：]|A[:：])");

  /** @param total 总分 0–100 @param detail 维度名 → 得分，顺序固定便于展示 */
  public record RuleScore(int total, Map<String, Integer> detail) {
    public int dimension(String name) {
      return detail.getOrDefault(name, 0);
    }
  }

  /** 计算一条清洗后正文的规则分；rawExcerpt 用于衡量清洗前的噪声量。 */
  public RuleScore score(String title, String body, String rawExcerpt) {
    int length = lengthScore(body);
    int completeness = completenessScore(body);
    int noise = noiseScore(body, rawExcerpt);
    int information = informationScore(body);
    int structure = structureScore(title, body);
    Map<String, Integer> detail = new LinkedHashMap<>();
    detail.put("length", length);
    detail.put("completeness", completeness);
    detail.put("noise", noise);
    detail.put("information", information);
    detail.put("structure", structure);
    int total = length + completeness + noise + information + structure;
    return new RuleScore(Math.min(100, Math.max(0, total)), detail);
  }

  /** 长度：舒适区间满分，过短或过长按比例降分。 */
  private static int lengthScore(String body) {
    int length = body.codePointCount(0, body.length());
    if (length < MIN_BODY) {
      return 0;
    }
    if (length <= COMFORTABLE_MIN) {
      return scale(length - MIN_BODY, COMFORTABLE_MIN - MIN_BODY, DIMENSION_MAX);
    }
    if (length <= COMFORTABLE_MAX) {
      return DIMENSION_MAX;
    }
    if (length >= MAX_BODY) {
      return 6;
    }
    return DIMENSION_MAX - scale(length - COMFORTABLE_MAX, MAX_BODY - COMFORTABLE_MAX, 14);
  }

  /** 完整性：有收尾标点、含约束类结构词，说明是一条可独立理解的表述。 */
  private static int completenessScore(String body) {
    int score = 10;
    if (SENTENCE_END.matcher(body.strip()).find()) {
      score += 5;
    }
    if (STRUCTURE_WORD.matcher(body).find()) {
      score += 5;
    }
    return Math.min(DIMENSION_MAX, score);
  }

  /** 噪声：残留标签、异常字符、重复标点、空白占比与清洗前后体积差都会扣分。 */
  private static int noiseScore(String body, String rawExcerpt) {
    int score = DIMENSION_MAX;
    if (TAG_RESIDUE.matcher(body).find()) {
      score -= 8;
    }
    if (body.contains("\uFFFD")) {
      score -= 8;
    }
    if (REPEATED_PUNCT.matcher(body).find()) {
      score -= 4;
    }
    if (blankRatio(body) > 0.3) {
      score -= 6;
    }
    if (rawExcerpt != null
        && rawExcerpt.length() > body.length() * 3 / 2
        && rawExcerpt.length() - body.length() > 40) {
      score -= 4;
    }
    return Math.max(0, score);
  }

  /** 清洗后仍残留的标签样式文本。 */
  private static final Pattern TAG_RESIDUE = Pattern.compile("<[^>]{1,120}>");
  private static final Pattern REPEATED_PUNCT = Pattern.compile("([。！？，、；：,.!?;:])\\1{2,}");

  /** 空白字符占比，空文本记为 0。 */
  private static double blankRatio(String text) {
    if (text.isEmpty()) {
      return 0;
    }
    long blanks = text.chars().filter(Character::isWhitespace).count();
    return (double) blanks / text.length();
  }

  /** 信息量：数字、日期、金额、期限、英文专名各命中一次得 4 分。 */
  private static int informationScore(String body) {
    int score = 0;
    if (body.chars().anyMatch(Character::isDigit)) {
      score += 4;
    }
    if (DATE.matcher(body).find()) {
      score += 4;
    }
    if (AMOUNT.matcher(body).find()) {
      score += 4;
    }
    if (DEADLINE.matcher(body).find()) {
      score += 4;
    }
    if (LATIN.matcher(body).find()) {
      score += 4;
    }
    return Math.min(DIMENSION_MAX, score);
  }

  /** 结构：标题、列表、字段行、问答标记与多句表达各 4 分。 */
  private static int structureScore(String title, String body) {
    int score = 0;
    if (!title.isBlank() && body.strip().length() > title.length()) {
      score += 4;
    }
    if (LIST_MARKER.matcher(body).find()) {
      score += 4;
    }
    if (FIELD_LINE.matcher(body).find()) {
      score += 4;
    }
    if (QA_MARKER.matcher(body).find()) {
      score += 4;
    }
    long sentenceEnds = body.chars().filter(ch -> "。！？!?".indexOf(ch) >= 0).count();
    if (sentenceEnds >= 2) {
      score += 4;
    }
    return Math.min(DIMENSION_MAX, score);
  }

  /** 按比例换算整数分，避免浮点直接落库。 */
  private static int scale(int value, int span, int max) {
    if (span <= 0) {
      return max;
    }
    return (int) Math.round((double) Math.max(0, Math.min(span, value)) / span * max);
  }
}
