package dev.aiqa.template;

/** 生成失败：code 用于落库 failure_code，retryable 决定是否交给任务队列重试。 */
public class TemplateGenerationFailure extends RuntimeException {

  private final String code;
  private final boolean retryable;

  public TemplateGenerationFailure(String code, boolean retryable, String message) {
    super(message);
    this.code = code;
    this.retryable = retryable;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}
