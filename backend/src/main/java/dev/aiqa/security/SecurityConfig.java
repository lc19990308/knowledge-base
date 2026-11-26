package dev.aiqa.security;

import dev.aiqa.common.ApiProblem;
import dev.aiqa.common.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
    type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

  @Bean
  SecurityFilterChain apiChain(
      HttpSecurity http,
      org.springframework.beans.factory.ObjectProvider<
              org.springframework.security.oauth2.client.registration.ClientRegistrationRepository>
          registrations,
      @org.springframework.beans.factory.annotation.Value("${aiqa.security.dev-header-auth.enabled:false}")
          boolean devHeaderAuth,
      @org.springframework.beans.factory.annotation.Value("${aiqa.session.absolute-timeout:PT12H}")
          java.time.Duration absoluteTimeout,
      AdminOnlyAuthorization adminOnly, ObjectMapper mapper)
      throws Exception {
    AccessDeniedHandler denied = (request, response, ex) -> writeProblem(response, mapper,
        ApiProblem.of(request, HttpStatus.FORBIDDEN,
            ex instanceof CsrfException ? ErrorCode.CSRF_INVALID : ErrorCode.FORBIDDEN, null));
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/providers",
                        "/api/v1/auth/captcha",
                        "/api/v1/auth/login",
                        "/error")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .access(adminOnly)
                    .anyRequest()
                    .authenticated())
        .csrf(
            csrf -> {
              CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
              repository.setHeaderName("X-CSRF-TOKEN");
              csrf.csrfTokenRepository(repository).ignoringRequestMatchers("/actuator/**");
            })
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint((request, response, ex) -> writeProblem(response, mapper,
                        ApiProblem.of(request, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, null)))
                    .accessDeniedHandler(denied))
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/v1/auth/logout")
                    .deleteCookies("QA_SESSION")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
        .headers(headers -> headers.frameOptions(frame -> frame.deny()));

    if (registrations.getIfAvailable() != null) {
      // SSO 成功后落到前端根路径（生产同源部署）；本地联调见 README 的 IdP 配置。
      http.oauth2Login(
          login ->
              login.successHandler(
                  (request, response, authentication) -> {
                    // 记录认证时刻；绝对有效期由 SessionExpiryFilter 检查，会话固定由框架策略处理。
                    request.getSession(true)
                        .setAttribute(SessionExpiryFilter.AUTH_TIME, java.time.Instant.now());
                    response.sendRedirect("/");
                  }));
    } else if (devHeaderAuth) {
      http.addFilterBefore(new DevHeaderAuthenticationFilter(), org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class);
    }
    // 绝对有效期对所有身份方式生效（含 OIDC 与本地账号）。
    http.addFilterBefore(new SessionExpiryFilter(absoluteTimeout), org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class);
    return http.build();
  }
  private static void writeProblem(jakarta.servlet.http.HttpServletResponse response,
      ObjectMapper mapper, ApiProblem problem) throws java.io.IOException {
    response.setStatus(problem.status());
    response.setHeader("X-Request-Id", problem.requestId());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), problem);
  }
}
