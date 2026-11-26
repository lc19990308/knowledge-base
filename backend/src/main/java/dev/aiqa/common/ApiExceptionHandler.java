package dev.aiqa.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private final io.micrometer.core.instrument.MeterRegistry meters;

  public ApiExceptionHandler(io.micrometer.core.instrument.MeterRegistry meters) {
    this.meters = meters;
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ApiProblem> handle(ApiException ex, HttpServletRequest request) {
    if (ex.status() == HttpStatus.FORBIDDEN || ex.status() == HttpStatus.NOT_FOUND) {
      meters.counter("aiqa.api.denied", "code", ex.code().name()).increment();
    }
    HttpHeaders headers = new HttpHeaders();
    ex.retryAfterSeconds().ifPresent(seconds -> headers.set(HttpHeaders.RETRY_AFTER, seconds.toString()));
    return problem(request, ex.status(), ex.code(), ex.getMessage(), headers);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
      HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    if (!(request instanceof ServletWebRequest servlet) || !isApi(servlet.getRequest())) {
      return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }
    HttpStatus status = HttpStatus.valueOf(statusCode.value());
    ErrorCode code = switch (status) {
      case BAD_REQUEST -> ErrorCode.INVALID_REQUEST;
      case NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
      case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
      case NOT_ACCEPTABLE -> ErrorCode.NOT_ACCEPTABLE;
      case PAYLOAD_TOO_LARGE -> ErrorCode.PAYLOAD_TOO_LARGE;
      case UNSUPPORTED_MEDIA_TYPE -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
      case SERVICE_UNAVAILABLE -> ErrorCode.DEPENDENCY_UNAVAILABLE;
      default -> ErrorCode.INTERNAL_ERROR;
    };
    if (status.is5xxServerError()) {
      LOG.error("API framework error", ex);
    }
    ResponseEntity<ApiProblem> response = problem(servlet.getRequest(), status, code, null, headers);
    return super.handleExceptionInternal(ex, response.getBody(), response.getHeaders(), status, request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiProblem> unexpected(Exception ex, HttpServletRequest request) throws Exception {
    if (!isApi(request) || ex instanceof org.springframework.security.core.AuthenticationException
        || ex instanceof org.springframework.security.access.AccessDeniedException) {
      throw ex;
    }
    LOG.error("Unexpected API error", ex);
    return problem(request, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, null,
        HttpHeaders.EMPTY);
  }

  private static boolean isApi(HttpServletRequest request) {
    return request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/");
  }

  private static ResponseEntity<ApiProblem> problem(HttpServletRequest request, HttpStatus status,
      ErrorCode code, String detail, HttpHeaders headers) {
    ApiProblem body = ApiProblem.of(request, status, code, detail);
    return ResponseEntity.status(status)
        .headers(headers)
        .headers(value -> value.set("X-Request-Id", body.requestId()))
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
