package dev.aiqa.answer;

import org.springframework.stereotype.Component;

/**
 * Assistant template wins over the global template; a blank value falls through. The built-in
 * default keeps the model constrained to evidence when neither is configured.
 */
@Component
public class TemplateResolver {

  public static final String DEFAULT_TEMPLATE =
      "只依据提供的资料回答；没有资料时明确说明无依据，不得编造。";

  public record Resolved(String template, String source) {}

  public Resolved resolve(String assistantTemplate, String globalTemplate) {
    if (assistantTemplate != null && !assistantTemplate.isBlank()) {
      return new Resolved(assistantTemplate, "ASSISTANT");
    }
    if (globalTemplate != null && !globalTemplate.isBlank()) {
      return new Resolved(globalTemplate, "GLOBAL");
    }
    return new Resolved(DEFAULT_TEMPLATE, "DEFAULT");
  }
}
