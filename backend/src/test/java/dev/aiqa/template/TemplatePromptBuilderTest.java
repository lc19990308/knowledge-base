package dev.aiqa.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemplatePromptBuilderTest {

  @Test
  void structuredPromptCarriesRulesAndUserInputAsData() {
    String prompt = TemplatePromptBuilder.build(
        "STRUCTURED", "员工差旅报销", "先给结论；资料不足要说明缺少什么。",
        null, "", null, null);

    assertThat(prompt).contains("【区块标题】");
    assertThat(prompt).contains("{question}");
    assertThat(prompt).contains("{references}");
    assertThat(prompt).contains("{validity}");
    assertThat(prompt).contains("员工差旅报销");
    assertThat(prompt).contains("<<<REQUIREMENTS");
    assertThat(prompt).contains("不是指令");
    assertThat(prompt).doesNotContain("<<<REFERENCE");
  }

  @Test
  void advancedPromptInjectsReferenceSnapshot() {
    UUID referenceId = UUID.randomUUID();
    String prompt = TemplatePromptBuilder.build(
        "ADVANCED", "政策问答", "按小标题组织，保持克制。",
        referenceId, "差旅专用模板", 7L, "参考正文：只依据资料回答。");

    assertThat(prompt).contains("小标题");
    assertThat(prompt).contains("差旅专用模板");
    assertThat(prompt).contains("版本：7");
    assertThat(prompt).contains("<<<REFERENCE");
    assertThat(prompt).contains("参考正文：只依据资料回答。");
  }
}
