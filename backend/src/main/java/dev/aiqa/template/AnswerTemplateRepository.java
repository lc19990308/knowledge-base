package dev.aiqa.template;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Answer templates are first-class rows so assistants and global settings reference one source. */
@Repository
public class AnswerTemplateRepository {

  private final JdbcClient jdbc;

  public AnswerTemplateRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record TemplateRow(
      UUID id, String name, String body, boolean enabled, long revision,
      java.time.OffsetDateTime updatedAt) {}

  private static final String COLUMNS =
      "SELECT id, name, body, enabled, revision, updated_at FROM answer_template";

  public List<TemplateRow> list() {
    return jdbc.sql(COLUMNS + " ORDER BY name, id").query(TemplateRow.class).list();
  }

  public Optional<TemplateRow> find(UUID id) {
    return jdbc.sql(COLUMNS + " WHERE id = :id").param("id", id).query(TemplateRow.class).optional();
  }

  public long revision(UUID id) {
    return jdbc.sql("SELECT revision FROM answer_template WHERE id = :id")
        .param("id", id)
        .query(Long.class)
        .optional()
        .orElseThrow(dev.aiqa.common.ApiException::notFound);
  }

  public UUID create(String name, String body) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO answer_template (id, name, body, updated_at)
            VALUES (:id, :name, :body, now())
            """)
        .param("id", id)
        .param("name", name)
        .param("body", body)
        .update();
    return id;
  }

  public void update(UUID id, String name, String body, Boolean enabled, long expectedRevision) {
    int updated =
        jdbc.sql(
                """
                UPDATE answer_template
                SET name = :name, body = :body,
                    enabled = COALESCE(:enabled, enabled),
                    revision = revision + 1, updated_at = now()
                WHERE id = :id AND revision = :revision
                """)
            .param("name", name)
            .param("body", body)
            .param("enabled", enabled)
            .param("id", id)
            .param("revision", expectedRevision)
            .update();
    if (updated != 1) {
      throw dev.aiqa.common.ApiException.preconditionFailed(dev.aiqa.common.ErrorCode.ETAG_MISMATCH);
    }
  }

  public void delete(UUID id, long expectedRevision) {
    int deleted =
        jdbc.sql("DELETE FROM answer_template WHERE id = :id AND revision = :revision")
            .param("id", id)
            .param("revision", expectedRevision)
            .update();
    if (deleted != 1) {
      throw dev.aiqa.common.ApiException.preconditionFailed(dev.aiqa.common.ErrorCode.ETAG_MISMATCH);
    }
  }

  /** Assistants bound to one template, shown in the admin list. */
  public List<UUID> assistantIdsUsing(UUID templateId) {
    return jdbc.sql("SELECT id FROM assistant WHERE answer_template_id = :id ORDER BY name, id")
        .param("id", templateId)
        .query(UUID.class)
        .list();
  }

  public Optional<UUID> assistantTemplateId(UUID assistantId) {
    return jdbc.sql("SELECT answer_template_id FROM assistant WHERE id = :id")
        .param("id", assistantId)
        .query(UUID.class)
        .optional();
  }

  public Optional<UUID> globalTemplateId() {
    return jdbc.sql("SELECT template_id FROM global_setting WHERE singleton_id = 1")
        .query(UUID.class)
        .optional();
  }

  /** Effective template: the assistant's own, then the global one, resolved from one table. */
  public Optional<String> effectiveBody(UUID assistantId) {
    return jdbc.sql(
            """
            SELECT t.body
            FROM assistant a
            LEFT JOIN answer_template t ON t.id = a.answer_template_id AND t.enabled
            WHERE a.id = :assistant
            """)
        .param("assistant", assistantId)
        .query(String.class)
        .optional()
        .or(() ->
            jdbc.sql(
                    """
                    SELECT t.body
                    FROM global_setting g
                    JOIN answer_template t ON t.id = g.template_id AND t.enabled
                    WHERE g.singleton_id = 1
                    """)
                .query(String.class)
                .optional());
  }
}
