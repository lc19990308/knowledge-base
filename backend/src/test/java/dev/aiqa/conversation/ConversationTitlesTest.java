package dev.aiqa.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationTitlesTest {

  @Test
  void stripsPolitenessAndTrailingQuestionMark() {
    assertThat(ConversationTitles.fromQuestion("  你好，请问差旅报销标准是多少？ "))
        .isEqualTo("差旅报销标准是多少");
    assertThat(ConversationTitles.fromQuestion("麻烦帮我查一下年假规定。"))
        .isEqualTo("查一下年假规定");
    assertThat(ConversationTitles.fromQuestion("你好")).isEqualTo("新会话");
    assertThat(ConversationTitles.fromQuestion(null)).isEqualTo("新会话");
  }

  @Test
  void keepsShortTitlesWithoutEllipsis() {
    assertThat(ConversationTitles.fromQuestion("年假规定")).isEqualTo("年假规定");
    String exact = "差旅报销标准与审批流程及所需材料说明汇总";
    assertThat(exact.codePointCount(0, exact.length()))
        .isLessThanOrEqualTo(ConversationTitles.MAX_CODE_POINTS);
    assertThat(ConversationTitles.fromQuestion(exact)).isEqualTo(exact);
  }

  @Test
  void truncatesLongQuestionsWithEllipsisAtPunctuationOrWordBoundary() {
    String question =
        "我想了解差旅报销的具体标准，包括住宿费、交通费和餐补的额度，以及报销需要提交哪些材料？";
    String title = ConversationTitles.fromQuestion(question);
    assertThat(title).endsWith("…");
    assertThat(title.codePointCount(0, title.length()))
        .isLessThanOrEqualTo(ConversationTitles.MAX_CODE_POINTS);
    assertThat(title).doesNotEndWith("，…").doesNotEndWith("？…");

    String english = "Explain the travel reimbursement policy for business trips and approvals";
    String truncated = ConversationTitles.truncate(english);
    assertThat(truncated).endsWith("…");
    assertThat(truncated.codePointCount(0, truncated.length()))
        .isLessThanOrEqualTo(ConversationTitles.MAX_CODE_POINTS);
    // 不把英文单词从中间截断
    assertThat(truncated).matches(".*[\\s][A-Za-z]+…$|.*[^A-Za-z]…$");
  }

  @Test
  void fallsBackToFirstAnswerSentenceWhenQuestionIsTooShort() {
    String answer =
        "根据当前可访问且已发布的资料：差旅报销需提交发票和审批单。住宿费按城市标准执行。";
    assertThat(ConversationTitles.fromExchange("怎么办？", answer))
        .isEqualTo("差旅报销需提交发票和审批单");
    // 问题本身信息足够时保持问题主题，不被回答覆盖
    assertThat(ConversationTitles.fromExchange("差旅报销需要哪些材料？", answer))
        .isEqualTo("差旅报销需要哪些材料");
    assertThat(ConversationTitles.fromExchange("怎么办", "")).isEqualTo("怎么办");
  }

  @Test
  void stripsAnswerLeadInAndCitationMarkers() {
    String answer = "根据资料，年假须提前三个工作日申请，由直属负责人审批 [1]。其他年假规定无依据。";
    assertThat(ConversationTitles.fromExchange("年假规定？", answer))
        .isEqualTo("年假须提前三个工作日申请，由直属负责人审批");
    assertThat(ConversationTitles.fromQuestion("如何理解规定[1]？")).isEqualTo("如何理解规定");
  }
}
