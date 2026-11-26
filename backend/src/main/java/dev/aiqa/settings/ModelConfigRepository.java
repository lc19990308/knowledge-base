package dev.aiqa.settings;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 模型配置（多条）：别名 + 服务端登记的凭据引用；global_setting 仅持有默认指针。 */
@Repository
public class ModelConfigRepository {

  private final JdbcClient jdbc;

  public ModelConfigRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record ConfigRow(
      UUID id, String alias, String credentialRef, boolean enabled, long revision,
      String protocol, String baseUrl, String modelName) {}

  private static final String COLUMNS =
      """
      SELECT id, alias, credential_ref AS \"credentialRef\", enabled, revision,
             protocol, base_url AS \"baseUrl\", model_name AS \"modelName\"
      FROM model_config
      """;

  public List<ConfigRow> list() {
    return jdbc.sql(COLUMNS + " ORDER BY created_at, id").query(ConfigRow.class).list();
  }

  public record ModelOption(UUID id, String alias, boolean isDefault) {}

  private static final String SELECTABLE =
      "enabled AND btrim(base_url) <> '' AND btrim(model_name) <> ''";

  public List<ModelOption> listSelectable() {
    return jdbc.sql("""
        SELECT id, alias, COALESCE(id = (
          SELECT default_model_id FROM global_setting WHERE singleton_id = 1
        ), false) AS "isDefault"
        FROM model_config WHERE
        """ + " " + SELECTABLE + " ORDER BY created_at, id").query(ModelOption.class).list();
  }

  public Optional<ConfigRow> findSelectable(UUID id) {
    return jdbc.sql(COLUMNS + " WHERE id = :id AND " + SELECTABLE)
        .param("id", id).query(ConfigRow.class).optional();
  }

  public Optional<ConfigRow> find(UUID id) {
    return jdbc.sql(COLUMNS + " WHERE id = :id")
        .param("id", id)
        .query(ConfigRow.class)
        .optional();
  }

  public boolean existsByAlias(String alias) {
    return jdbc.sql("SELECT count(*) FROM model_config WHERE alias = :alias")
            .param("alias", alias)
            .query(Long.class)
            .single()
        > 0;
  }

  public long revision(UUID id) {
    return jdbc.sql("SELECT revision FROM model_config WHERE id = :id")
        .param("id", id)
        .query(Long.class)
        .optional()
        .orElse(0L);
  }

  public UUID create(
      String alias, String credentialRef, boolean enabled,
      String protocol, String baseUrl, String modelName) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO model_config (id, alias, credential_ref, enabled, protocol, base_url, model_name)
            VALUES (:id, :alias, :credential, :enabled, :protocol, :baseUrl, :modelName)
            """)
        .param("id", id)
        .param("alias", alias)
        .param("credential", credentialRef)
        .param("enabled", enabled)
        .param("protocol", protocol)
        .param("baseUrl", baseUrl)
        .param("modelName", modelName)
        .update();
    return id;
  }

  public void update(
      UUID id, String alias, String credentialRef, boolean enabled, long expectedRevision,
      String protocol, String baseUrl, String modelName) {
    int updated =
        jdbc.sql(
                """
                UPDATE model_config SET alias = :alias, credential_ref = :credential,
                       enabled = :enabled, protocol = :protocol, base_url = :baseUrl,
                       model_name = :modelName,
                       revision = revision + 1, updated_at = now()
                WHERE id = :id AND revision = :revision
                """)
            .param("id", id)
            .param("alias", alias)
            .param("credential", credentialRef)
            .param("enabled", enabled)
            .param("protocol", protocol)
            .param("baseUrl", baseUrl)
            .param("modelName", modelName)
            .param("revision", expectedRevision)
            .update();
    if (updated != 1) {
      throw dev.aiqa.common.ApiException.preconditionFailed(dev.aiqa.common.ErrorCode.ETAG_MISMATCH);
    }
  }

  public void delete(UUID id, long expectedRevision) {
    int deleted =
        jdbc.sql("DELETE FROM model_config WHERE id = :id AND revision = :revision")
            .param("id", id)
            .param("revision", expectedRevision)
            .update();
    if (deleted != 1) {
      throw dev.aiqa.common.ApiException.preconditionFailed(dev.aiqa.common.ErrorCode.ETAG_MISMATCH);
    }
  }

  /** 默认模型指针指向的那条配置；未配置时为空。 */
  public java.util.Optional<ConfigRow> findDefaultConfig() {
    return jdbc.sql(
            """
            SELECT m.id, m.alias, m.credential_ref AS \"credentialRef\", m.enabled, m.revision,
                   m.protocol, m.base_url AS \"baseUrl\", m.model_name AS \"modelName\"
            FROM global_setting g JOIN model_config m ON m.id = g.default_model_id
            WHERE g.singleton_id = 1
            """)
        .query(ConfigRow.class)
        .optional();
  }

  /** 该配置是否为全局默认指针。 */
  public boolean isDefault(UUID id) {
    return jdbc.sql(
            "SELECT count(*) FROM global_setting WHERE singleton_id = 1 AND default_model_id = :id")
            .param("id", id)
            .query(Long.class)
            .single()
        > 0;
  }
}
