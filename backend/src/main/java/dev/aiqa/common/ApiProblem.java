package dev.aiqa.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(String type, String title, int status, ErrorCode code, String requestId,
                         String detail) {
  public static ApiProblem of(HttpServletRequest request, HttpStatus status, ErrorCode code,
      String detail) {
    return new ApiProblem("about:blank", status.getReasonPhrase(), status.value(), code,
        RequestIdFilter.requestId(request), detail == null || detail.isBlank() ? null : detail);
  }
}
