package dev.aiqa.auth;

import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

  private final JdbcClient jdbc;

  public UserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<AppUser> findBySubject(String issuer, String subject) {
    return jdbc.sql(
            """
            SELECT id, issuer, subject, display_name, enabled, can_create_space, auth_version
            FROM app_user WHERE issuer = :issuer AND subject = :subject
            """)
        .param("issuer", issuer)
        .param("subject", subject)
        .query(AppUserRow.class)
        .optional()
        .map(row -> row.toUser(roles(row.id())));
  }

  public Optional<AppUser> findById(UUID id) {
    return jdbc.sql(
            """
            SELECT id, issuer, subject, display_name, enabled, can_create_space, auth_version
            FROM app_user WHERE id = :id
            """)
        .param("id", id)
        .query(AppUserRow.class)
        .optional()
        .map(row -> row.toUser(roles(row.id())));
  }

  private List<String> roles(UUID userId) {
    return jdbc.sql("SELECT role FROM user_role WHERE user_id = :id ORDER BY role")
        .param("id", userId)
        .query(String.class)
        .list();
  }

  /** Creates the local account on first successful login; never elevates roles. */
  /** 本地账号由管理员显式创建：issuer='local'，subject 即用户名，默认普通角色。 */
  public UUID createLocal(String username, String displayName) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO app_user (id, issuer, subject, display_name)
            VALUES (:id, :issuer, :subject, :name)
            """)
        .param("id", id)
        .param("issuer", "local")
        .param("subject", username)
        .param("name", displayName)
        .update();
    return id;
  }

  public AppUser provision(AuthenticatedUser principal) {
    Optional<AppUser> existing = findBySubject(principal.issuer(), principal.subject());
    if (existing.isPresent()) {
      return existing.get();
    }
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO app_user (id, issuer, subject, display_name)
            VALUES (:id, :issuer, :subject, :name)
            """)
        .param("id", id)
        .param("issuer", principal.issuer())
        .param("subject", principal.subject())
        .param("name", principal.displayName())
        .update();
    jdbc.sql("INSERT INTO user_role (user_id, role) VALUES (:id, 'USER')").param("id", id).update();
    return findById(id).orElseThrow(ApiException::notFound);
  }

  public void updateAccess(UUID userId, boolean enabled, List<String> roles, boolean canCreateSpace) {
    List<String> normalized = roles.stream().distinct().sorted().toList();
    if (normalized.isEmpty() || normalized.stream().anyMatch(r -> !r.equals("USER") && !r.equals("SYSTEM_ADMIN"))) {
      throw ApiException.invalid("角色仅允许 USER 或 SYSTEM_ADMIN");
    }
    jdbc.sql(
            """
            UPDATE app_user
            SET enabled = :enabled, can_create_space = :create, auth_version = auth_version + 1
            WHERE id = :id
            """)
        .param("enabled", enabled)
        .param("create", canCreateSpace)
        .param("id", userId)
        .update();
    jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", userId).update();
    for (String role : normalized) {
      jdbc.sql("INSERT INTO user_role (user_id, role) VALUES (:id, :role)")
          .param("id", userId)
          .param("role", role)
          .update();
    }
  }

  public long activeAdminCountExcluding(UUID userId) {
    return jdbc.sql(
            """
            SELECT count(*) FROM app_user u
            JOIN user_role r ON r.user_id = u.id
            WHERE r.role = 'SYSTEM_ADMIN' AND u.enabled AND u.id <> :id
            """)
        .param("id", userId)
        .query(Long.class)
        .single();
  }

  public List<AppUser> search(String query, int limit) {
    String like = "%" + query.toLowerCase() + "%";
    List<AppUserRow> rows =
        jdbc.sql(
                """
                SELECT id, issuer, subject, display_name, enabled, can_create_space, auth_version
                FROM app_user
                WHERE lower(display_name) LIKE :like OR id::text = :exact
                ORDER BY display_name, id
                LIMIT :limit
                """)
            .param("like", like)
            .param("exact", query)
            .param("limit", limit)
            .query(AppUserRow.class)
            .list();
    return rows.stream().map(row -> row.toUser(roles(row.id()))).toList();
  }

  public void requireEnabled(AppUser user) {
    if (!user.enabled()) {
      throw new ApiException(ErrorCode.ACCOUNT_DISABLED, HttpStatus.FORBIDDEN, "账号已停用");
    }
  }

  record AppUserRow(
      UUID id,
      String issuer,
      String subject,
      String displayName,
      boolean enabled,
      boolean canCreateSpace,
      long authVersion) {
    AppUser toUser(List<String> roles) {
      return new AppUser(id, issuer, subject, displayName, enabled, canCreateSpace, authVersion, roles);
    }
  }
}
