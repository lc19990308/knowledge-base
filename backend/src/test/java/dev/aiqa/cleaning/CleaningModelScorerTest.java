package dev.aiqa.cleaning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 模型打分的解析与降级：一行 JSON、越界钳制、脏响应。 */
class CleaningModelScorerTest {

  @Test
  void parsesOpenAiCompatibleResponse() {
    String body =
        "{\"choices\":[{\"message\":{\"content\":\"{\\\"score\\\":87,\\\"reason\\\":\\\"表达完整\\\"}\"}}]}";

    assertThat(CleaningModelScorer.parse(body, "openai_compatible"))
        .contains(new CleaningModelScorer.ModelScore(87, "表达完整"));
  }

  @Test
  void parsesAnthropicResponse() {
    String body =
        "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"score\\\":72,\\\"reason\\\":\\\"信息偏少\\\"}\"}]}";

    assertThat(CleaningModelScorer.parse(body, "anthropic"))
        .contains(new CleaningModelScorer.ModelScore(72, "信息偏少"));
  }

  @Test
  void clampsOutOfRangeScores() {
    assertThat(CleaningModelScorer.parse(content("{\"score\":120}"), "openai_compatible"))
        .contains(new CleaningModelScorer.ModelScore(100, ""));
    assertThat(CleaningModelScorer.parse(content("{\"score\":-5}"), "openai_compatible"))
        .contains(new CleaningModelScorer.ModelScore(0, ""));
  }

  @Test
  void acceptsStringScoresFromChattyModels() {
    assertThat(
            CleaningModelScorer.parse(
                content("{\"score\":\"85\",\"reason\":\"可用\"}"), "openai_compatible"))
        .contains(new CleaningModelScorer.ModelScore(85, "可用"));
  }

  @Test
  void treatsUnparseableContentAsUnavailable() {
    assertThat(CleaningModelScorer.parse(content("抱歉，我无法评分"), "openai_compatible"))
        .isEmpty();
    assertThat(CleaningModelScorer.parse("not-json", "openai_compatible")).isEmpty();
  }

  @Test
  void userPromptTruncatesLongBody() {
    String body = "甲".repeat(5000);
    String prompt = CleaningModelScorer.userPrompt("标题", body);

    assertThat(prompt).contains("标题：标题");
    assertThat(prompt.length()).isLessThan(1400);
    assertThat(prompt).endsWith("…");
  }

  /** 把模型回复包进 OpenAI 兼容响应，模拟网关原样返回。 */
  private static String content(String modelContent) {
    return "{\"choices\":[{\"message\":{\"content\":"
        + new tools.jackson.databind.ObjectMapper().writeValueAsString(modelContent)
        + "}}]}";
  }
}
