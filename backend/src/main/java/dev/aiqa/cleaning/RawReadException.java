package dev.aiqa.cleaning;

/** 单个来源文件读取失败；错误码会原样写进 cleaning_source_file.error_code，供界面提示。 */
public class RawReadException extends RuntimeException {

  private final String code;

  public RawReadException(String code) {
    this(code, code);
  }

  public RawReadException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
