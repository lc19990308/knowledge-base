package dev.aiqa.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String ATTRIBUTE = "aiqa.requestId";

  public static String requestId(HttpServletRequest request) {
    if (request.getAttribute(ATTRIBUTE) instanceof String value) {
      return value;
    }
    String value = UUID.randomUUID().toString();
    request.setAttribute(ATTRIBUTE, value);
    return value;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = request.getHeader("X-Request-Id");
    if (requestId == null || requestId.isBlank() || requestId.length() > 160) {
      requestId = UUID.randomUUID().toString();
    }
    request.setAttribute(ATTRIBUTE, requestId);
    response.setHeader("X-Request-Id", requestId);
    org.slf4j.MDC.put("requestId", requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      org.slf4j.MDC.remove("requestId");
    }
  }
}
