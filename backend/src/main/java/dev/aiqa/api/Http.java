package dev.aiqa.api;

import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public final class Http {

  private Http() {}

  /** Parses a strong If-Match value; missing means 428, malformed or stale means 412. */
  public static long requireIfMatch(String header) {
    if (header == null || header.isBlank()) {
      throw new ApiException(ErrorCode.IF_MATCH_REQUIRED, HttpStatus.PRECONDITION_REQUIRED);
    }
    String value = header.strip();
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      throw new ApiException(ErrorCode.ETAG_MISMATCH, HttpStatus.PRECONDITION_FAILED);
    }
  }

  public static String etag(long revision) {
    return "\"" + revision + "\"";
  }

  public static UUID pathUuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ex) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, field + " 不是合法 UUID");
    }
  }
}
