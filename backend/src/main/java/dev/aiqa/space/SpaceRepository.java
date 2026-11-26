package dev.aiqa.space;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SpaceRepository {

  private final JdbcClient jdbc;

  public SpaceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record SpaceRow(
      UUID id,
      UUID ownerId,
      String name,
      long stateVersion,
      long publicationEpoch,
      UUID currentPublicationId,
      boolean canRead,
      boolean canMaintain,
      java.time.OffsetDateTime updatedAt,
      String department) {
    public boolean isReadable() {
      return canRead && currentPublicationId != null;
    }
  }

  public record Membership(boolean canRead, boolean canMaintain) {}

  public UUID create(UUID ownerId, String name, String department) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO space (id, owner_id, name, department) VALUES (:id, :owner, :name, :department)
            """)
        .param("id", id)
        .param("owner", ownerId)
        .param("name", name)
        .param("department", department)
        .update();
    jdbc.sql(
            """
            INSERT INTO space_member (space_id, user_id, can_read, can_maintain, granted_by)
            VALUES (:space, :user, TRUE, TRUE, :user)
            """)
        .param("space", id)
        .param("user", ownerId)
        .update();
    jdbc.sql(
            """
            INSERT INTO space_draft (space_id, updated_by) VALUES (:space, :user)
            """)
        .param("space", id)
        .param("user", ownerId)
        .update();
    return id;
  }

  /**
   * Reads the space with the caller's membership in the same statement so authorization and data
   * cannot be separated by a later write.
   *
   * <p>系统管理员默认拥有全部空间的最高权限：即使空间成员表里没有记录，读取与维护也按 TRUE
   * 计算。这条规则放在同一个语句里，任何按空间校验权限的入口都自动继承，不需要各自再判断。
   */
  public Optional<SpaceRow> findForUser(UUID spaceId, UUID userId) {
    return jdbc.sql(
            """
            WITH actor AS (
              SELECT EXISTS (
                SELECT 1 FROM user_role WHERE user_id = :user AND role = 'SYSTEM_ADMIN'
              ) AS is_admin
            )
            SELECT s.id, s.owner_id, s.name, s.state_version, s.publication_epoch,
                   s.current_publication_id, s.updated_at, s.department,
                   (a.is_admin OR COALESCE(m.can_read, FALSE)) AS can_read,
                   (a.is_admin OR COALESCE(m.can_maintain, FALSE)) AS can_maintain
            FROM space s
            CROSS JOIN actor a
            LEFT JOIN space_member m ON m.space_id = s.id AND m.user_id = :user
            WHERE s.id = :space
            """)
        .param("space", spaceId)
        .param("user", userId)
        .query(SpaceRow.class)
        .optional();
  }

  public Optional<Membership> membership(UUID spaceId, UUID userId) {
    return jdbc.sql(
            """
            SELECT can_read, can_maintain FROM space_member
            WHERE space_id = :space AND user_id = :user
            """)
        .param("space", spaceId)
        .param("user", userId)
        .query(Membership.class)
        .optional();
  }

  public List<SpaceRow> listForUser(UUID userId) {
    return jdbc.sql(
            """
            WITH actor AS (
              SELECT EXISTS (
                SELECT 1 FROM user_role WHERE user_id = :user AND role = 'SYSTEM_ADMIN'
              ) AS is_admin
            )
            SELECT s.id, s.owner_id, s.name, s.state_version, s.publication_epoch,
                   s.current_publication_id, s.updated_at, s.department,
                   (a.is_admin OR COALESCE(m.can_read, FALSE)) AS can_read,
                   (a.is_admin OR COALESCE(m.can_maintain, FALSE)) AS can_maintain
            FROM space s
            CROSS JOIN actor a
            LEFT JOIN space_member m ON m.space_id = s.id AND m.user_id = :user
            WHERE a.is_admin OR m.can_maintain
              OR (m.can_read AND s.current_publication_id IS NOT NULL)
            ORDER BY s.name, s.id
            """)
        .param("user", userId)
        .query(SpaceRow.class)
        .list();
  }

  public void setMembership(
      UUID spaceId, UUID userId, boolean canRead, boolean canMaintain, UUID grantedBy) {
    if (!canRead && !canMaintain) {
      jdbc.sql("DELETE FROM space_member WHERE space_id = :space AND user_id = :user")
          .param("space", spaceId)
          .param("user", userId)
          .update();
      return;
    }
    jdbc.sql(
            """
            INSERT INTO space_member (space_id, user_id, can_read, can_maintain, granted_by, updated_at)
            VALUES (:space, :user, :read, :maintain, :granted, now())
            ON CONFLICT (space_id, user_id) DO UPDATE
              SET can_read = :read, can_maintain = :maintain, granted_by = :granted, updated_at = now()
            """)
        .param("space", spaceId)
        .param("user", userId)
        .param("read", canRead)
        .param("maintain", canMaintain)
        .param("granted", grantedBy)
        .update();
  }

  public List<MemberRow> members(UUID spaceId) {
    return jdbc.sql(
            """
            SELECT u.id AS user_id, u.display_name, m.can_read, m.can_maintain
            FROM space_member m JOIN app_user u ON u.id = m.user_id
            WHERE m.space_id = :space
            ORDER BY u.display_name, u.id
            """)
        .param("space", spaceId)
        .query(MemberRow.class)
        .list();
  }

  public record MemberRow(UUID userId, String displayName, boolean canRead, boolean canMaintain) {}

  /**
   * 某成员在各空间的授权，用于系统设置的一次性多空间分配。{@code owned} 为真表示该空间由该用户
   * 创建，其权限不能被管理员收回，界面上也只做展示。
   */
  public record UserSpaceRow(
      UUID spaceId, String spaceName, boolean canRead, boolean canMaintain, boolean owned) {}

  public List<UserSpaceRow> membershipsOfUser(UUID userId) {
    return jdbc.sql(
            """
            SELECT s.id AS space_id, s.name AS space_name, m.can_read, m.can_maintain,
                   (s.owner_id = :user) AS owned
            FROM space_member m
            JOIN space s ON s.id = m.space_id
            WHERE m.user_id = :user
            ORDER BY s.name, s.id
            """)
        .param("user", userId)
        .query(UserSpaceRow.class)
        .list();
  }

  public List<UUID> ownedSpaceIds(UUID userId) {
    return jdbc.sql("SELECT id FROM space WHERE owner_id = :user ORDER BY id")
        .param("user", userId)
        .query(UUID.class)
        .list();
  }

  /** 清空成员在「非本人创建」空间上的授权，用于批量覆盖；本人创建的空间保留所有者权限。 */
  public int clearMembershipsExceptOwned(UUID userId) {
    return jdbc.sql(
            """
            DELETE FROM space_member m
            USING space s
            WHERE m.space_id = s.id AND m.user_id = :user AND s.owner_id <> :user
            """)
        .param("user", userId)
        .update();
  }

  public record DocumentStats(long documents, long searchable) {}

  /**
   * Published spaces report the current snapshot; unpublished ones report the draft, so the
   * workbench never mixes visible content with unapproved material.
   */
  public DocumentStats documentStats(UUID spaceId, boolean published) {
    if (published) {
      return jdbc.sql(
              """
              SELECT count(*) AS documents,
                     count(*) FILTER (WHERE v.parse_state = 'READY') AS searchable
              FROM space s
              JOIN space_publication p ON p.id = s.current_publication_id AND p.space_id = s.id
              JOIN space_snapshot_document d ON d.snapshot_id = p.snapshot_id AND d.space_id = s.id
              JOIN document_version v ON v.id = d.document_version_id AND v.space_id = d.space_id
              WHERE s.id = :space
              """)
          .param("space", spaceId)
          .query(DocumentStats.class)
          .single();
    }
    return jdbc.sql(
            """
            SELECT count(*) AS documents,
                   count(*) FILTER (WHERE v.parse_state = 'READY') AS searchable
            FROM space_draft_document d
            JOIN document_version v ON v.id = d.document_version_id AND v.space_id = d.space_id
            WHERE d.space_id = :space
            """)
        .param("space", spaceId)
        .query(DocumentStats.class)
        .single();
  }

  /** Takes the parent row lock used by every membership, publish and withdraw mutation. */
  public void lockSpace(UUID spaceId) {
    jdbc.sql("SELECT id FROM space WHERE id = :id FOR UPDATE").param("id", spaceId).query(UUID.class).optional();
  }

  public void bumpState(UUID spaceId) {
    jdbc.sql("UPDATE space SET state_version = state_version + 1, updated_at = now() WHERE id = :id")
        .param("id", spaceId)
        .update();
  }
}
