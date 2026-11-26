package dev.aiqa.cleaning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 清洗规则：去噪、拆条、标题与规则分边界。 */
class CleaningRulesTest {

  private final TextCleaner cleaner = new TextCleaner();
  private final ItemSplitter splitter = new ItemSplitter(cleaner);
  private final RuleScorer scorer = new RuleScorer();

  @Test
  void cleanerRemovesMarkupEntitiesAndRepeatedPunctuation() {
    TextCleaner.Cleaned cleaned =
        cleaner.clean("<p>差旅标准&nbsp;：一线城市 600 元。。。</p>");

    assertThat(cleaned.text()).isEqualTo("差旅标准 ：一线城市 600 元。");
    assertThat(cleaned.removedTags()).isEqualTo(2);
    assertThat(cleaned.collapsedPunctuation()).isEqualTo(1);
  }

  @Test
  void cleanerStripsControlAndZeroWidthCharacters() {
    TextCleaner.Cleaned cleaned = cleaner.clean("差旅\u200B标准\u0007：住宿 600 元");

    assertThat(cleaned.text()).doesNotContain("\u200B").doesNotContain("\u0007");
    assertThat(cleaned.removedOddChars()).isEqualTo(2);
  }

  @Test
  void splitterMergesShortBlocksIntoTheNextOne() {
    List<ItemSplitter.Candidate> items =
        splitter.split(
            new RawRecord(
                "差旅标准\n\n一线城市住宿每晚上限六百元，需凭住宿发票与行程单在十五个工作日内报销。",
                "差旅/住宿.csv:2"));

    assertThat(items).hasSize(1);
    assertThat(items.get(0).body()).contains("差旅标准").contains("十五个工作日");
    assertThat(items.get(0).title()).isEqualTo("差旅标准");
    assertThat(items.get(0).location()).isEqualTo("差旅/住宿.csv:2");
  }

  @Test
  void splitterBreaksOversizedBlocksOnSentenceBoundaries() {
    String sentence = "差旅报销应在行程结束后十五个工作日内提交申请。";
    String block = sentence.repeat(300);

    List<ItemSplitter.Candidate> items = splitter.split(new RawRecord(block, "长文.txt"));

    assertThat(items).hasSizeGreaterThan(1);
    assertThat(items).allSatisfy(item -> assertThat(item.body().length()).isLessThanOrEqualTo(4000));
  }

  @Test
  void titleStripsMarkdownAndListMarkers() {
    assertThat(ItemSplitter.titleOf("## 差旅住宿标准\n正文内容……")).isEqualTo("差旅住宿标准");
    assertThat(ItemSplitter.titleOf("- 差旅住宿标准：一线城市 600 元"))
        .isEqualTo("差旅住宿标准：一线城市 600 元");
  }

  @Test
  void ruleScoreStaysWithinBoundsAndRewardsStructure() {
    String clean =
        "差旅住宿标准：一线城市每晚上限 600 元，二线城市 400 元。\n"
            + "报销要求：需提供住宿发票与行程单，行程金额需与发票一致。\n"
            + "1. 发票抬头须为公司全称；2. 发票日期需在行程结束后十五个工作日内；"
            + "3. 超出上限的部分由员工自理。";
    RuleScorer.RuleScore rich = scorer.score("差旅住宿标准", clean, clean);

    assertThat(rich.total()).isBetween(0, 100);
    assertThat(rich.dimension("length")).isEqualTo(20);
    // 结构分：有标题、字段行、列表与多句表达；问答标记不在本例中，故按 16 断言下限。
    assertThat(rich.dimension("structure")).isGreaterThanOrEqualTo(16);

    String noisy = "<b>住宿</b>。。。" + "  ".repeat(40) + "标准";
    RuleScorer.RuleScore poor = scorer.score("住宿", noisy, "<b>住宿</b>。。。标准");
    assertThat(poor.dimension("noise")).isLessThan(rich.dimension("noise"));
    assertThat(poor.total()).isLessThan(rich.total());
  }

  @Test
  void ruleScoreOfVeryShortTextIsZeroOnLength() {
    RuleScorer.RuleScore score = scorer.score("短", "太短", "太短");
    assertThat(score.dimension("length")).isZero();
  }
}
