package dev.aiqa.assistant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AssistantRepository {

  private final JdbcClient jdbc;

  public AssistantRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record AssistantRow(
      UUID id, String name, boolean enabled, UUID templateId, String template, long revision) {}

  public UUID create(String name, UUID templateId) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO assistant (id, name, answer_template_id) VALUES (:id, :name, :template)")
        .param("id", id).param("name", name).param("template", templateId).update();
    return id;
  }

  /** The joined body is read-only here; writes go through the template repository. */
  public Optional<AssistantRow> find(UUID id) {
    return jdbc.sql(ROW_QUERY + " WHERE a.id = :id")
        .param("id", id).query(AssistantRow.class).optional();
  }

  /** 管理员视角：包含已停用的助手。 */
  public List<AssistantRow> listAll() {
    return jdbc.sql(ROW_QUERY + " ORDER BY a.name, a.id").query(AssistantRow.class).list();
  }

  public List<AssistantRow> list() {
    return jdbc.sql(ROW_QUERY + " ORDER BY a.name, a.id").query(AssistantRow.class).list();
  }

  private static final String ROW_QUERY =
      """
      SELECT a.id, a.name, a.enabled, a.answer_template_id AS template_id,
             t.body AS template, a.revision
      FROM assistant a
      LEFT JOIN answer_template t ON t.id = a.answer_template_id AND t.enabled
      """;

  public long revision(UUID id) {
    return jdbc.sql("SELECT revision FROM assistant WHERE id = :id").param("id", id).query(Long.class).single();
  }

  public void update(UUID id, String name, UUID templateId, Boolean enabled, long expectedRevision) {
    int updated =
        jdbc.sql(
                """
                UPDATE assistant SET name = :name, answer_template_id = :template,
                       enabled = COALESCE(:enabled, enabled),
                       revision = revision + 1, updated_at = now()
                WHERE id = :id AND revision = :revision
                """)
            .param("name", name).param("template", templateId).param("enabled", enabled)
            .param("id", id).param("revision", expectedRevision).update();
    if (updated != 1) {
      throw dev.aiqa.common.ApiException.preconditionFailed(dev.aiqa.common.ErrorCode.ETAG_MISMATCH);
    }
  }

  public List<UUID> boundSpaceIds(UUID assistantId) {
    return jdbc.sql("SELECT space_id FROM assistant_space WHERE assistant_id = :id ORDER BY space_id")
        .param("id", assistantId).query(UUID.class).list();
  }

  /** 绑定的空间及其名称，供接口回填，避免客户端为显示名称再拉全量空间。 */
  public record BoundSpace(UUID id, String name) {}

  public List<BoundSpace> boundSpaces(UUID assistantId) {
    return jdbc.sql(
            """
            SELECT s.id, s.name
            FROM assistant_space a
            JOIN space s ON s.id = a.space_id
            WHERE a.assistant_id = :id
            ORDER BY s.name, s.id
            """)
        .param("id", assistantId).query(BoundSpace.class).list();
  }

  public void replaceBindings(UUID assistantId, List<UUID> spaceIds) {
    jdbc.sql("DELETE FROM assistant_space WHERE assistant_id = :id").param("id", assistantId).update();
    for (UUID spaceId : spaceIds) {
      jdbc.sql(
              """
              INSERT INTO assistant_space (assistant_id, space_id, binding_role)
              VALUES (:assistant, :space, 'RETRIEVAL')
              """)
          .param("assistant", assistantId).param("space", spaceId).update();
    }
  }

  public Optional<UUID> anyEnabled() {
    return jdbc.sql("SELECT id FROM assistant WHERE enabled ORDER BY created_at, id LIMIT 1")
        .query(UUID.class).optional();
  }
}
