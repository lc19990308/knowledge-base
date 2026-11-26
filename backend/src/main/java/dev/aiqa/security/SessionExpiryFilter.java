package dev.aiqa.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 绝对有效期（2026-11-22 落实后端设计 §5 目标）：登录成功时在会话写入认证时刻，
 * 本过滤器发现超过绝对时限即作废会话并清空上下文，后续请求自然得到 401。
 * 属性缺失的历史会话不强制下线；开发头身份每请求重建认证，不受影响。
 */
public final class SessionExpiryFilter extends OncePerRequestFilter {

  public static final String AUTH_TIME = "aiqa.authenticatedAt";

  private final Duration maxAge;

  SessionExpiryFilter(Duration maxAge) {
    this.maxAge = maxAge;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.isAuthenticated()) {
      HttpSession session = request.getSession(false);
      if (session != null && session.getAttribute(AUTH_TIME) instanceof Instant authenticatedAt
          && Duration.between(authenticatedAt, Instant.now()).compareTo(maxAge) > 0) {
        session.invalidate();
        SecurityContextHolder.clearContext();
      }
    }
    chain.doFilter(request, response);
  }
}
