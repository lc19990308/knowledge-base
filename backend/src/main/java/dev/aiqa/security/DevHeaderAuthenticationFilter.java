package dev.aiqa.security;

import dev.aiqa.auth.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Development-only identity shim. It is registered solely when
 * aiqa.security.dev-header-auth.enabled=true and the app runs with the dev profile; production must
 * use OIDC. The header is never trusted outside that configuration.
 */
final class DevHeaderAuthenticationFilter extends OncePerRequestFilter {

  static final String PRINCIPAL = "devIdentity";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String subject = request.getHeader("X-Dev-Subject");
    if (subject != null && !subject.isBlank()) {
      // Development-only: the header selects the identity even when a session already exists so
      // separate personas can be exercised in one browser. Production never registers this filter.
      String issuer = headerOr(request, "X-Dev-Issuer", "https://dev.local/oidc");
      String displayName = headerOr(request, "X-Dev-Name", subject);
      AuthenticatedUser principal = new AuthenticatedUser(issuer, subject, displayName);
      var authentication =
          UsernamePasswordAuthenticationToken.authenticated(
              principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
      SecurityContextHolder.getContext().setAuthentication(authentication);
    }
    chain.doFilter(request, response);
  }

  private static String headerOr(HttpServletRequest request, String name, String fallback) {
    String value = request.getHeader(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
