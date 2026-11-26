package dev.aiqa.conversation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {

  private final JdbcClient jdbc;

  public ConversationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record ConversationRow(
      UUID id, UUID ownerId, UUID assistantId, String title, OffsetDateTime createdAt, boolean archived) {}

  public UUID create(UUID ownerId, UUID assistantId, String title) {
    return create(ownerId, assistantId, title, title == null);
  }

  /**
   * autoTitle 为 true 时，标题由首个问题与首轮回答自动提炼，后台可继续更新；
   * 用户显式命名的会话保持原样。
   */
  public UUID create(UUID ownerId, UUID assistantId, String title, boolean autoTitle) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO conversation (id, owner_id, assistant_id, title, title_auto)
            VALUES (:id, :owner, :assistant, :title, :auto)
            """)
        .param("id", id).param("owner", ownerId).param("assistant", assistantId)
        .param("title", title).param("auto", autoTitle).update();
    return id;
  }

  private static final String SELECT_FOR_OWNER = """
      SELECT id, owner_id, assistant_id, title, created_at, archived FROM conversation
      WHERE id = :id AND owner_id = :owner AND deleted_at IS NULL
      """;

  public Optional<ConversationRow> findForOwner(UUID id, UUID ownerId) {
    return jdbc.sql(SELECT_FOR_OWNER)
        .param("id", id).param("owner", ownerId).query(ConversationRow.class).optional();
  }

  /** Holds the conversation state stable through question admission and commit. */
  public Optional<ConversationRow> lockForOwner(UUID id, UUID ownerId) {
    return jdbc.sql(SELECT_FOR_OWNER + " FOR NO KEY UPDATE")
        .param("id", id).param("owner", ownerId).query(ConversationRow.class).optional();
  }

  public List<ConversationRow> listForOwner(UUID ownerId, boolean archived) {
    return jdbc.sql(
            """
            SELECT id, owner_id, assistant_id, title, created_at, archived FROM conversation
            WHERE owner_id = :owner AND archived = :archived AND deleted_at IS NULL
            ORDER BY updated_at DESC, id
            """)
        .param("owner", ownerId).param("archived", archived).query(ConversationRow.class).list();
  }

  public Optional<ConversationRow> setArchived(UUID id, UUID ownerId, boolean archived) {
    return jdbc.sql(
            """
            UPDATE conversation SET archived = :archived
            WHERE id = :id AND owner_id = :owner AND deleted_at IS NULL
            RETURNING id, owner_id, assistant_id, title, created_at, archived
            """)
        .param("id", id).param("owner", ownerId).param("archived", archived)
        .query(ConversationRow.class).optional();
  }

  public boolean softDelete(UUID id, UUID ownerId) {
    return jdbc.sql(
            """
            UPDATE conversation SET deleted_at = now()
            WHERE id = :id AND owner_id = :owner AND deleted_at IS NULL
            """)
        .param("id", id).param("owner", ownerId).update() == 1;
  }

  public void touch(UUID id) {
    jdbc.sql("UPDATE conversation SET updated_at = now() WHERE id = :id AND deleted_at IS NULL")
        .param("id", id).update();
  }

  /**
   * 自动标题只覆盖系统生成且仍只有首个问题的会话；用户显式命名或已进行的多轮对话保持原样。
   *
   * @return 是否实际更新
   */
  public boolean setGeneratedTitle(UUID id, String title) {
    if (title == null || title.isBlank()) {
      return false;
    }
    return jdbc.sql(
            """
            UPDATE conversation c SET title = :title, updated_at = now()
            WHERE c.id = :id AND c.title_auto AND c.deleted_at IS NULL
              AND (SELECT count(*) FROM question q WHERE q.conversation_id = c.id) <= 1
            """)
        .param("title", title).param("id", id).update() == 1;
  }
}
