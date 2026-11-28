package dev.aiqa.answer;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 判定器的开关、旁路、协议解析与失败开放；不访问网络。 */
class ModelRelevanceJudgeTest {

  private final MockEnvironment environment = new MockEnvironment();

  private ModelRelevanceJudge judge(String mode) {
    return judge(mode, "closed");
  }

  private ModelRelevanceJudge judge(String mode, String failureMode) {
    return new ModelRelevanceJudge(
        environment, new SimpleMeterRegistry(), mode, Duration.ofSeconds(2), failureMode,
        10, 800);
  }

  private static RelevanceJudge.Model model() {
    return new RelevanceJudge.Model(
        "openai_compatible", "http://127.0.0.1:9/v1", "test-model", "test");
  }

  @Test
  void offAndFakeModesNeverBlock() {
    assertThat(judge("off").judge(model(), "问题", List.of("资料")).sufficient()).isTrue();
    assertThat(judge("fake").judge(model(), "问题", List.of("资料")).sufficient()).isTrue();
  }

  @Test
  void withoutConfiguredGatewayItBypassesInsteadOfBlocking() {
    var verdict = judge("model").judge(null, "问题", List.of("资料"));
    assertThat(verdict.sufficient()).isTrue();
    assertThat(verdict.reason()).contains("未配置模型");
  }

  @Test
  void missingCredentialFailsClosedByDefault() {
    environment.setProperty("aiqa.answer.relevance", "model");
    var verdict = judge("model").judge(model(), "问题", List.of("资料"));
    assertThat(verdict.sufficient()).isFalse();
    assertThat(verdict.available()).isFalse();
  }

  @Test
  void failureModeOpenKeepsAnswering() {
    environment.setProperty("aiqa.answer.relevance", "model");
    var verdict = judge("model", "open").judge(model(), "问题", List.of("资料"));
    assertThat(verdict.sufficient()).isTrue();
    assertThat(verdict.available()).isFalse();
  }

  @Test
  void promptNumbersEvidenceAndTruncates() {
    String longText = "一".repeat(900);
    String prompt = judge("model").userPrompt("早餐能报多少？", List.of("第一条", longText));
    assertThat(prompt).contains("问题：早餐能报多少？").contains("[1] 第一条").contains("[2] ");
    assertThat(prompt).contains("…");
    assertThat(prompt.length()).isLessThan(1500);
  }

  @Test
  void parsesOpenAiStyleVerdictAndUsage() {
    String body =
        """
        {"choices":[{"message":{"content":"{\\"sufficient\\":false,\\"reason\\":\\"资料没有早餐额度\\"}"}}],
         "usage":{"prompt_tokens":120,"completion_tokens":18}}
        """;
    var verdict = judge("model").parse(body, "openai_compatible");
    assertThat(verdict.sufficient()).isFalse();
    assertThat(verdict.available()).isTrue();
    assertThat(verdict.reason()).contains("早餐");
    assertThat(verdict.inputTokens()).isEqualTo(120L);
    assertThat(verdict.outputTokens()).isEqualTo(18L);
  }

  @Test
  void parsesAnthropicStyleVerdict() {
    String body =
        """
        {"content":[{"type":"text","text":"{\\"sufficient\\":true,\\"reason\\":\\"条款直接支持\\"}"}],
         "usage":{"input_tokens":90,"output_tokens":12}}
        """;
    var verdict = judge("model").parse(body, "anthropic");
    assertThat(verdict.sufficient()).isTrue();
    assertThat(verdict.inputTokens()).isEqualTo(90L);
  }

  @Test
  void unparseableVerdictFailsClosed() {
    var verdict = judge("model").parse("{\"choices\":[{\"message\":{\"content\":\"我说不准\"}}]}",
        "openai_compatible");
    assertThat(verdict.sufficient()).isFalse();
    assertThat(verdict.available()).isFalse();
  }

  @Test
  void unparseableVerdictFailsOpenWhenConfigured() {
    var verdict = judge("model", "open").parse(
        "{\"choices\":[{\"message\":{\"content\":\"我说不准\"}}]}", "openai_compatible");
    assertThat(verdict.sufficient()).isTrue();
    assertThat(verdict.available()).isFalse();
  }

  @Test
  void acceptsBooleanAsText() {
    var verdict = judge("model").parse(
        "{\"choices\":[{\"message\":{\"content\":\"{\\\"sufficient\\\":\\\"false\\\"}\"}}]}",
        "openai_compatible");
    assertThat(verdict.sufficient()).isFalse();
  }
}
