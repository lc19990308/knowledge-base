package dev.aiqa.answer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 证据充分性与排布。
 *
 * <p>关键词召回天然会带入只是"顺带出现"该词的片段：例如「差旅早餐能报多少」会命中「金额含税，不含早餐」，
 * 「绒冠蜥保育周期」会命中含「天／多少」的任意段落。把这类候选直接交给模型，会把无关片段写成有依据的回答。
 * 这里用两条可核对、可回归的规则拦在没有依据的回答之前：
 *
 * <ul>
 *   <li>数量问法里，实词只出现在否定语境（不含／不包括／除外……）时不能支撑"是多少"的肯定结论；
 *   <li>引号内的名称（公司名、文档名、界面标签）不算条款依据；
 *   <li>实词全部未命中时，只有问题与资料还共享至少两个非虚词才放行——这覆盖"出完差回来交单子"
 *       这类口语改写，而纯属无关主题的问题会在这里被拒答。
 * </ul>
 *
 * <p>规则只做保守拦截：任何一个实词被肯定命中即放行，因此正常问法不会被误拒。
 */
public final class EvidenceSupport {

  /** 只保留能承载语义的实词；单字与常见虚词既噪声大又几乎处处命中。 */
  private static final Set<String> STOPWORDS = Set.of(
      "可以", "多少", "什么", "怎么", "如何", "时候", "以后", "之后", "之内", "以上", "以下",
      "是不", "不算", "是否", "分别", "现在", "目前", "我们", "你们", "他们", "这个", "那个",
      "哪些", "哪个", "为什", "么样", "不是", "需要", "应当", "应该", "一个", "几个");

  /** 否定语境标记；只出现在这些词之后的实词不能作为肯定依据。 */
  private static final List<String> NEGATIONS = List.of(
      "不含", "不包括", "不包含", "不提供", "不予", "不适用", "除外", "不涉及", "无需");

  /** 否定标记与实词之间允许的间隔，避免把远处另一个分句的否定算到本词上。 */
  private static final int NEGATION_WINDOW = 4;

  /** 单字虚词不参与"至少命中两个词"的兜底判断：它们几乎处处出现，不能说明主题相关。 */
  private static final Set<String> FUNCTION_CHARS = Set.of(
      "的", "了", "是", "在", "我", "你", "他", "它", "把", "要", "会", "得", "完", "去", "上",
      "来", "和", "与", "或", "也", "都", "就", "还", "这", "那", "个", "几", "多", "少", "什",
      "么", "时", "候", "之", "内", "后", "前", "用", "可", "以", "不", "算", "吗", "呢", "吧",
      "请", "给", "对", "从", "到", "为", "及", "等", "其", "此", "该");

  /** 引号内的名称（公司名、文档名、界面标签）常被检索词误命中，不作为条款依据。 */
  private static final java.util.regex.Pattern QUOTED =
      java.util.regex.Pattern.compile("「[^」]*」|『[^』]*』|\"[^\"]*\"");

  /**
   * 数量/金额问法。这类问题的核心词若只出现在否定语境（「不含早餐」），
   * 语料并没有给出"多少"，只能拒答；纯是非问句（「含税吗」）不受此限。
   */
  private static final Pattern QUANTITY_CUE = Pattern.compile(
      "多少|几|元|金额|额度|单价|上限|标准|预算|比例|费用");

  private EvidenceSupport() {}

  /** 一个候选证据：文档名用于估算词的稀有度，正文用于判断是否被肯定命中。 */
  public record Candidate(String documentName, String text) {}

  /**
   * 判定结果。insufficient 为真时调用方必须输出无依据段落且不写任何引用。
   * reason 只用于日志与测试断言，不进入用户文案。
   */
  public record Assessment(
      boolean sufficient,
      List<String> contentTerms,
      List<String> positiveTerms,
      List<String> negatedOnlyTerms,
      String reason) {}

  /** 问题里参与判定的实词，按首次出现顺序去重。 */
  public static List<String> contentTerms(List<String> tokens) {
    Set<String> terms = new LinkedHashSet<>();
    for (String token : tokens) {
      if (token == null) {
        continue;
      }
      String term = token.strip().toLowerCase(Locale.ROOT);
      if (term.codePointCount(0, term.length()) < 2 || STOPWORDS.contains(term)) {
        continue;
      }
      terms.add(term);
    }
    return List.copyOf(terms);
  }

  public static Assessment assess(
      String question, List<String> tokens, List<Candidate> candidates) {
    List<String> terms = contentTerms(tokens);
    if (terms.isEmpty()) {
      return new Assessment(true, terms, List.of(), List.of(), "问题没有可判定的实词");
    }
    if (candidates.isEmpty()) {
      return new Assessment(false, terms, List.of(), List.of(), "没有候选证据");
    }
    List<String> provenTexts = candidates.stream()
        .map(candidate -> stripQuoted(candidate.text()))
        .toList();
    List<String> positive = new ArrayList<>();
    List<String> negatedOnly = new ArrayList<>();
    for (String term : terms) {
      int occurrences = 0;
      boolean positiveOccurrence = false;
      for (String text : provenTexts) {
        int from = 0;
        while (true) {
          int at = text.indexOf(term, from);
          if (at < 0) {
            break;
          }
          occurrences++;
          if (!negated(text, at)) {
            positiveOccurrence = true;
          }
          from = at + term.length();
        }
      }
      if (positiveOccurrence) {
        positive.add(term);
      } else if (occurrences > 0) {
        negatedOnly.add(term);
      }
    }
    if (!negatedOnly.isEmpty() && asksForQuantity(question)) {
      return new Assessment(false, terms, positive, negatedOnly,
          "数量问法的核心实词只出现在否定语境：" + String.join("、", negatedOnly));
    }
    if (positive.isEmpty()) {
      // 口语改写常把实词换成同义说法（"出完差回来"），此时不能仅凭字面判定无依据；
      // 但至少要能和资料共享两个非虚词，才认为问题确实指向这批资料。
      long shared = tokens.stream()
          .filter(EvidenceSupport::countsAsShared)
          .filter(token -> appearsInAny(stripQuoted(token), provenTexts))
          .count();
      if (shared >= 2) {
        return new Assessment(true, terms, positive, negatedOnly,
            "实词未直接命中，但与资料共享 " + shared + " 个词");
      }
      return new Assessment(false, terms, positive, negatedOnly, "没有实词被证据支持");
    }
    return new Assessment(true, terms, positive, negatedOnly, "存在被肯定支持的实词");
  }

  private static boolean countsAsShared(String token) {
    String value = token == null ? "" : token.strip().toLowerCase(Locale.ROOT);
    if (value.isEmpty() || STOPWORDS.contains(value)) {
      return false;
    }
    return value.codePointCount(0, value.length()) > 1 || !FUNCTION_CHARS.contains(value);
  }

  private static boolean appearsInAny(String token, List<String> texts) {
    return !token.isEmpty() && texts.stream().anyMatch(text -> text.contains(token));
  }

  /** 去掉引号内的名称，避免公司名、文档名被当成条款依据。 */
  private static String stripQuoted(String text) {
    if (text == null) {
      return "";
    }
    return QUOTED.matcher(text).replaceAll(" ").toLowerCase(Locale.ROOT);
  }

  private static boolean asksForQuantity(String question) {
    return question != null && QUANTITY_CUE.matcher(question).find();
  }

  /** 词的否定语境：紧邻前 {@value #NEGATION_WINDOW} 个字符内出现否定标记。 */
  private static boolean negated(String text, int at) {
    String window = text.substring(Math.max(0, at - NEGATION_WINDOW), at);
    return NEGATIONS.stream().anyMatch(window::endsWith)
        || NEGATIONS.stream().anyMatch(window::contains);
  }

  /**
   * 覆盖优先的重排：先取覆盖问题实词最多的一片，再每次挑"新增覆盖最多"的一片。
   *
   * <p>这样复合问题（住宿限额 + 报销时限）的两个方面都会落到靠前的候选，而不是被同一份文档的
   * 多个相近片段占满；单一方面的问题顺序基本不变。
   */
  public static <T> List<T> rerank(
      List<String> tokens, List<T> candidates, java.util.function.Function<T, String> textOf) {
    List<String> terms = contentTerms(tokens);
    if (candidates.size() < 2 || terms.isEmpty()) {
      return candidates;
    }
    List<T> remaining = new ArrayList<>(candidates);
    List<T> ordered = new ArrayList<>(candidates.size());
    Set<String> covered = new LinkedHashSet<>();
    while (!remaining.isEmpty()) {
      T best = null;
      int bestGain = -1;
      for (T candidate : remaining) {
        int gain = 0;
        String text = String.valueOf(textOf.apply(candidate)).toLowerCase(Locale.ROOT);
        for (String term : terms) {
          if (!covered.contains(term) && text.contains(term)) {
            gain++;
          }
        }
        if (gain > bestGain) {
          best = candidate;
          bestGain = gain;
        }
      }
      if (best == null) {
        break;
      }
      ordered.add(best);
      remaining.remove(best);
      String text = String.valueOf(textOf.apply(best)).toLowerCase(Locale.ROOT);
      for (String term : terms) {
        if (text.contains(term)) {
          covered.add(term);
        }
      }
    }
    ordered.addAll(remaining);
    return ordered;
  }
}
