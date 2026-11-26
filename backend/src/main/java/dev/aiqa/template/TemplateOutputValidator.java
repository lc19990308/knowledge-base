package dev.aiqa.template;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型输出清理与校验：剥掉代码围栏、去空白、限制长度、只允许白名单变量。
 * 校验失败抛出不可重试的 {@link TemplateGenerationFailure}，避免为错误输出反复付费。
 */
public final class TemplateOutputValidator {

  private static final Set<String> ALLOWED_VARIABLES = Set.of("question", "references", "validity");
  private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]{0,64})\\}");
  private static final Pattern OPEN_FENCE = Pattern.compile("^```[a-zA-Z0-9_-]*\\s*\\n");
  private static final Pattern CLOSE_FENCE = Pattern.compile("\\n```\\s*$");

  private TemplateOutputValidator() {}

  public static String validate(String raw) {
    if (raw == null) {
      throw new TemplateGenerationFailure("INVALID_MODEL_OUTPUT", false, "模型没有返回内容");
    }
    String text = raw.strip();
    text = OPEN_FENCE.matcher(text).replaceFirst("");
    text = CLOSE_FENCE.matcher(text).replaceFirst("");
    text = text.strip();
    if (text.isEmpty()) {
      throw new TemplateGenerationFailure("INVALID_MODEL_OUTPUT", false, "模型返回了空模板");
    }
    if (text.length() > TemplatePromptBuilder.MAX_BODY_LENGTH) {
      throw new TemplateGenerationFailure(
          "INVALID_MODEL_OUTPUT", false, "模型返回的模板超过长度上限");
    }
    Matcher matcher = VARIABLE.matcher(text);
    while (matcher.find()) {
      String name = matcher.group(1);
      if (!ALLOWED_VARIABLES.contains(name)) {
        throw new TemplateGenerationFailure(
            "INVALID_MODEL_OUTPUT", false, "模型返回了不支持的变量：{" + name + "}");
      }
    }
    return text;
  }
}
