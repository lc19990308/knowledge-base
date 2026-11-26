package dev.aiqa.common;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

  private final ErrorCode code;
  private final HttpStatus status;
  private final Long retryAfterSeconds;

  public ApiException(ErrorCode code, HttpStatus status, String detail) {
    this(code, status, detail, null);
  }

  public ApiException(ErrorCode code, HttpStatus status, String detail, Long retryAfterSeconds) {
    super(detail);
    this.code = code;
    this.status = status;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public java.util.Optional<Long> retryAfterSeconds() {
    return java.util.Optional.ofNullable(retryAfterSeconds);
  }

  public ApiException(ErrorCode code, HttpStatus status) {
    this(code, status, null);
  }

  public ErrorCode code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  public static ApiException notFound() {
    return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND);
  }

  public static ApiException forbidden() {
    return new ApiException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN);
  }

  public static ApiException invalid(String detail) {
    return new ApiException(ErrorCode.VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_ENTITY, detail);
  }

  public static ApiException preconditionFailed(ErrorCode code) {
    return new ApiException(code, HttpStatus.PRECONDITION_FAILED);
  }

  public static ApiException conflict(ErrorCode code) {
    return new ApiException(code, HttpStatus.CONFLICT);
  }

  public static ApiException cancel(String detail) {
    return new ApiException(ErrorCode.STATE_CONFLICT, HttpStatus.CONFLICT, detail);
  }

  public static UUID uuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ex) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, field + " 不是合法 UUID");
    }
  }
}
