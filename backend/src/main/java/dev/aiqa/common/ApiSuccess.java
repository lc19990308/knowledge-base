package dev.aiqa.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiSuccess<T>(String code, T data, String requestId) {
  public static <T> ApiSuccess<T> of(T data, String requestId) {
    return new ApiSuccess<>("OK", data, requestId);
  }
}
