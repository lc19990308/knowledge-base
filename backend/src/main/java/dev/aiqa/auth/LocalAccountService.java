package dev.aiqa.auth;

import dev.aiqa.common.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地账号体系（2026-11-22 决策修订）：与企业 OIDC 并存。issuer 固定 'local'，subject 即用户名；
 * 密码只存 BCrypt 哈希。登录失败按用户名做固定窗口限流，成功即重置。
 */
@Service
public class LocalAccountService {

  static final String ISSUER = "local";
  private static final int PASSWORD_MIN = 8;
  private static final int PASSWORD_MAX = 72;

  private final JdbcClient jdbc;
  private final UserRepository users;
  private final dev.aiqa.publish.AuditRepository audit;
  private final PasswordEncoder encoder = new BCryptPasswordEncoder();
  private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
  private final int maxFailedAttempts;
  private final Map<String, Window> failures = new ConcurrentHashMap<>();

  public LocalAccountService(
      JdbcClient jdbc,
      UserRepository users,
      dev.aiqa.publish.AuditRepository audit,
      @Value("${aiqa.login.max-failed-attempts:10}") int maxFailedAttempts) {
    this.jdbc = jdbc;
    this.users = users;
    this.audit = audit;
    this.maxFailedAttempts = maxFailedAttempts;
  }

  /** 登录：成功建立会话并返回用户；失败统一 401，不区分用户名不存在与密码错误。 */
  @Transactional
  public AppUser login(
      String username, String password, jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response) {
    String name = username == null ? "" : username.strip();
    String secret = password == null ? "" : password;
    checkAttempts(name);
    AppUser user = users.findBySubject(ISSUER, name).orElse(null);
    String stored = passwordHash(user == null ? null : user.id());
    boolean matches = user != null && stored != null && encoder.matches(secret, stored);
    if (!matches) {
      recordFailure(name);
      audit.record(user == null ? null : user.id(), "LOGIN_FAILED", "AUTH",
          user == null ? UUID.randomUUID() : user.id(), Map.of("username", name));
      throw new ApiException(dev.aiqa.common.ErrorCode.INVALID_CREDENTIALS,
          org.springframework.http.HttpStatus.UNAUTHORIZED);
    }
    users.requireEnabled(user);
    failures.remove(name);
    establishSession(user, request, response);
    audit.record(user.id(), "LOGIN", "AUTH", user.id(), Map.of("issuer", ISSUER));
    return user;
  }

  /** 管理员创建本地账号；角色仅限 USER / SYSTEM_ADMIN。 */
  @Transactional
  public AppUser createLocalUser(String username, String displayName, String password, List<String> roles) {
    String name = username == null ? "" : username.strip();
    if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,63}")) {
      throw dev.aiqa.common.ApiException.invalid("用户名须为 3–64 位字母数字或 . _ -");
    }
    validatePassword(password);
    if (users.findBySubject(ISSUER, name).isPresent()) {
      throw new ApiException(dev.aiqa.common.ErrorCode.USERNAME_TAKEN,
          org.springframework.http.HttpStatus.CONFLICT);
    }
    UUID userId = users.createLocal(name, displayName == null ? "" : displayName.strip());
    jdbc.sql("INSERT INTO user_credential (user_id, password_hash) VALUES (:user, :hash)")
        .param("user", userId)
        .param("hash", encoder.encode(password))
        .update();
    List<String> effective =
        roles == null || roles.isEmpty() ? List.of("USER") : roles.stream().distinct().toList();
    for (String role : effective) {
      if (!"USER".equals(role) && !"SYSTEM_ADMIN".equals(role)) {
        throw dev.aiqa.common.ApiException.invalid("角色仅支持 USER、SYSTEM_ADMIN");
      }
      jdbc.sql("INSERT INTO user_role (user_id, role) VALUES (:user, :role)")
          .param("user", userId)
          .param("role", role)
          .update();
    }
    return users.findById(userId).orElseThrow();
  }

  /** 管理员重置本地账号密码；企业 IdP 账号不适用。 */
  @Transactional
  public void resetPassword(UUID userId, String newPassword, AppUser actor) {
    validatePassword(newPassword);
    AppUser target = users.findById(userId).orElseThrow(dev.aiqa.common.ApiException::notFound);
    if (!ISSUER.equals(target.issuer())) {
      throw dev.aiqa.common.ApiException.invalid("企业账号的凭据由 IdP 管理，不能在此重置密码");
    }
    int updated =
        jdbc.sql(
                """
                INSERT INTO user_credential (user_id, password_hash) VALUES (:user, :hash)
                ON CONFLICT (user_id) DO UPDATE SET password_hash = :hash, updated_at = now()
                """)
            .param("user", userId)
            .param("hash", encoder.encode(newPassword))
            .update();
    if (updated != 1) {
      throw dev.aiqa.common.ApiException.invalid("密码更新失败");
    }
    audit.record(actor.id(), "PASSWORD_RESET", "USER", userId, Map.of());
  }

  private void establishSession(
      AppUser user, jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response) {
    AuthenticatedUser principal =
        new AuthenticatedUser(ISSUER, user.subject(), user.displayName());
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    // 会话轮换（Servlet 6.1 无 changeSessionId）：作废旧会话，把上下文保存进新会话，防会话固定。
    var existing = request.getSession(false);
    if (existing != null) {
      existing.invalidate();
    }
    contextRepository.saveContext(context, request, response);
    // 认证时刻供 SessionExpiryFilter 的绝对有效期检查。
    request.getSession().setAttribute(
        dev.aiqa.security.SessionExpiryFilter.AUTH_TIME, java.time.Instant.now());
  }

  private String passwordHash(UUID userId) {
    if (userId == null) {
      return null;
    }
    return jdbc.sql("SELECT password_hash FROM user_credential WHERE user_id = :user")
        .param("user", userId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private void validatePassword(String password) {
    if (password == null || password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
      throw dev.aiqa.common.ApiException.invalid(
          "密码长度须为 8–72 个字符");
    }
  }

  private void checkAttempts(String username) {
    Window window = failures.get(username);
    if (window == null) {
      return;
    }
    if (Duration.between(window.startedAt(), Instant.now()).compareTo(Duration.ofSeconds(60)) > 0) {
      failures.remove(username);
      return;
    }
    if (window.count() >= maxFailedAttempts) {
      throw new ApiException(dev.aiqa.common.ErrorCode.RATE_LIMITED,
          org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
    }
  }

  private void recordFailure(String username) {
    failures.compute(
        username,
        (key, window) ->
            window == null
                    || Duration.between(window.startedAt(), Instant.now())
                        .compareTo(Duration.ofSeconds(60)) > 0
                ? new Window(Instant.now(), 1)
                : new Window(window.startedAt(), window.count() + 1));
  }

  private record Window(Instant startedAt, int count) {}
}
