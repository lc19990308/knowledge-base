package dev.aiqa.api;

import dev.aiqa.auth.AppUser;
import dev.aiqa.auth.CurrentUser;
import dev.aiqa.auth.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AdminUserController {

  private final CurrentUser current;
  private final UserRepository users;
  private final dev.aiqa.publish.AuditRepository audit;
  private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
  private final dev.aiqa.auth.LocalAccountService localAccounts;

  public AdminUserController(
      CurrentUser current,
      UserRepository users,
      dev.aiqa.publish.AuditRepository audit,
      org.springframework.jdbc.core.simple.JdbcClient jdbc,
      dev.aiqa.auth.LocalAccountService localAccounts) {
    this.current = current;
    this.users = users;
    this.audit = audit;
    this.jdbc = jdbc;
    this.localAccounts = localAccounts;
  }

  public record AccessInput(Boolean enabled, List<String> roles, Boolean canCreateSpace) {}

  public record AccessView(
      String userId, String displayName, boolean enabled, List<String> roles,
      boolean canCreateSpace, String email, String issuer) {}

  public record LocalUserInput(String username, String displayName, String password, List<String> roles) {}

  public record CredentialInput(String password) {}

  private AccessView view(AppUser user) {
    String email =
        jdbc.sql("SELECT email FROM app_user WHERE id = :id")
            .param("id", user.id())
            .query(String.class)
            .optional()
            .orElse(null);
    return new AccessView(
        user.id().toString(), user.displayName(), user.enabled(), user.roles(),
        user.canCreateSpace(), email, user.issuer());
  }

  /** 管理员创建本地账号（用户名+初始密码），普通用户随后即可在登录页登录。 */
  @PostMapping("/users")
  public ResponseEntity<AccessView> createLocalUser(@RequestBody LocalUserInput input) {
    current.requireAdmin();
    AppUser created =
        localAccounts.createLocalUser(
            input.username(), input.displayName(), input.password(), input.roles());
    return ResponseEntity.status(HttpStatus.CREATED).body(view(created));
  }

  /** 管理员重置本地账号密码；企业账号返回 400。 */
  @PostMapping("/users/{userId}/credential")
  public ResponseEntity<Void> resetCredential(
      @PathVariable String userId, @RequestBody CredentialInput input) {
    AppUser admin = current.requireAdmin();
    localAccounts.resetPassword(Http.pathUuid(userId, "userId"), input.password(), admin);
    return ResponseEntity.noContent().build();
  }

  /** 用户查找：命中上限仍为 50 人（见契约），limit/offset 在该范围内分页。 */
  @GetMapping("/users")
  public dev.aiqa.common.PageView<AccessView> list(
      @RequestParam(name = "q", required = false, defaultValue = "") String query,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {
    current.requireAdmin();
    dev.aiqa.common.PageQuery page = dev.aiqa.common.PageQuery.of(limit, offset);
    List<AppUser> rows = users.search(query.strip(), USER_SEARCH_LIMIT);
    List<AccessView> items = page.slice(rows).stream().map(this::view).toList();
    return dev.aiqa.common.PageView.of(items, rows.size());
  }

  private static final int USER_SEARCH_LIMIT = 50;

  @PutMapping("/users/{userId}/access")
  public AccessView update(@PathVariable String userId, @RequestBody AccessInput input) {
    AppUser admin = current.requireAdmin();
    UUID id = Http.pathUuid(userId, "userId");
    AppUser target = users.findById(id).orElseThrow(dev.aiqa.common.ApiException::notFound);
    List<String> roles = input.roles() == null ? target.roles() : input.roles();
    boolean enabled = input.enabled() == null || input.enabled();
    boolean canCreate = input.canCreateSpace() != null && input.canCreateSpace();
    boolean removingAdmin = target.isSystemAdmin() && (!roles.contains("SYSTEM_ADMIN") || !enabled);
    if (removingAdmin && users.activeAdminCountExcluding(id) == 0) {
      throw dev.aiqa.common.ApiException.conflict(dev.aiqa.common.ErrorCode.STATE_CONFLICT);
    }
    users.updateAccess(id, enabled, roles, canCreate);
    audit.record(admin.id(), "USER_ACCESS_UPDATED", "app_user", id,
        java.util.Map.of("enabled", Boolean.toString(enabled),
            "roles", String.join(",", roles),
            "canCreateSpace", Boolean.toString(canCreate)));
    return view(users.findById(id).orElseThrow());
  }
}
