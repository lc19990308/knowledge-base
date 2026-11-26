package dev.aiqa.common;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice(basePackages = "dev.aiqa.api")
public class ApiSuccessAdvice implements ResponseBodyAdvice<Object> {
  @Override
  public boolean supports(MethodParameter returnType,
      Class<? extends HttpMessageConverter<?>> converterType) {
    return JacksonJsonHttpMessageConverter.class.isAssignableFrom(converterType);
  }

  @Override
  public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
      Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
      ServerHttpResponse response) {
    if (!(request instanceof ServletServerHttpRequest servletRequest)
        || !(response instanceof ServletServerHttpResponse servletResponse)) {
      return body;
    }
    int status = servletResponse.getServletResponse().getStatus();
    if (status < 200 || status >= 300 || status == 204 || body instanceof ApiSuccess<?>) {
      return body;
    }
    return ApiSuccess.of(body, RequestIdFilter.requestId(servletRequest.getServletRequest()));
  }
}
