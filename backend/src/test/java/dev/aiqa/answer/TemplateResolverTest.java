package dev.aiqa.answer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TemplateResolverTest {

  private final TemplateResolver resolver = new TemplateResolver();

  @Test
  void assistantTemplateWinsThenGlobalThenDefault() {
    assertThat(resolver.resolve("助手模板", "全局模板"))
        .isEqualTo(new TemplateResolver.Resolved("助手模板", "ASSISTANT"));
    assertThat(resolver.resolve("   ", "全局模板"))
        .isEqualTo(new TemplateResolver.Resolved("全局模板", "GLOBAL"));
    assertThat(resolver.resolve(null, null))
        .isEqualTo(new TemplateResolver.Resolved(TemplateResolver.DEFAULT_TEMPLATE, "DEFAULT"));
  }
}
