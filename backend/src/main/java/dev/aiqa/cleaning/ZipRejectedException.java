package dev.aiqa.cleaning;

/** 压缩包整体被拒绝；错误码写进上传响应，说明是哪个限制触发的。 */
public class ZipRejectedException extends RuntimeException {

  private final String code;

  public ZipRejectedException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
