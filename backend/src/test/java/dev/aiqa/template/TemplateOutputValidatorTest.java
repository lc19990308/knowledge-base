package dev.aiqa.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TemplateOutputValidatorTest {

  @Test
  void stripsMarkdownFenceAndTrims() {
    String body = TemplateOutputValidator.validate(
        "```text\n【回答要求】\n先给结论。\n\n【用户问题】\n{question}\n```");
    assertThat(body).isEqualTo("【回答要求】\n先给结论。\n\n【用户问题】\n{question}");
  }

  @Test
  void rejectsUnknownVariable() {
    assertThatThrownBy(() -> TemplateOutputValidator.validate("请回答：{question}，参考 {citations}。"))
        .isInstanceOf(TemplateGenerationFailure.class)
        .hasMessageContaining("不支持的变量");
  }

  @Test
  void rejectsEmptyAndOversizedOutput() {
    assertThatThrownBy(() -> TemplateOutputValidator.validate("   "))
        .isInstanceOf(TemplateGenerationFailure.class);
    String tooLong = "定".repeat(TemplatePromptBuilder.MAX_BODY_LENGTH + 1);
    assertThatThrownBy(() -> TemplateOutputValidator.validate(tooLong))
        .isInstanceOf(TemplateGenerationFailure.class)
        .hasMessageContaining("长度上限");
  }

  @Test
  void acceptsAllSupportedVariables() {
    assertThat(TemplateOutputValidator.validate(
            "{question}\n{references}\n{validity}"))
        .isEqualTo("{question}\n{references}\n{validity}");
  }
}
