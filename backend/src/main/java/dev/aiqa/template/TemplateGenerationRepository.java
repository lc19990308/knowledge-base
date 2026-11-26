package dev.aiqa.template;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * AI 模板生成记录。快照字段（requested_by_name、model_alias、reference_template_*、prompt_text）
 * 只在创建时写入，之后所有更新都不得触碰，保证历史可还原。
 */
@Repository
public class TemplateGenerationRepository {

  private final JdbcClient jdbc;

  public TemplateGenerationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record Row(
      UUID id, UUID requestedBy, String requestedByName, String mode, String scenario,
      String requirements, UUID referenceTemplateId, String referenceTemplateName,
      Long referenceTemplateRevision, UUID modelConfigId, String modelAlias, String promptText,
      String status, String failureCode, String resultBody, Long inputTokens, Long outputTokens,
      OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime finishedAt) {}

  private static final String COLUMNS =
      """
      SELECT id, requested_by AS "requestedBy", requested_by_name AS "requestedByName",
             mode, scenario, requirements, reference_template_id AS "referenceTemplateId",
             reference_template_name AS "referenceTemplateName",
             reference_template_revision AS "referenceTemplateRevision",
             model_config_id AS "modelConfigId", model_alias AS "modelAlias",
             prompt_text AS "promptText", status, failure_code AS "failureCode",
             result_body AS "resultBody", input_tokens AS "inputTokens",
             output_tokens AS "outputTokens", created_at AS "createdAt",
             started_at AS "startedAt", finished_at AS "finishedAt"
      FROM answer_template_generation
      """;

  public Optional<Row> find(UUID id) {
    return jdbc.sql(COLUMNS + " WHERE id = :id").param("id", id).query(Row.class).optional();
  }

  public void create(
      UUID id, String requestKey, UUID requestedBy, String requestedByName, String mode,
      String scenario, String requirements, UUID referenceTemplateId,
      String referenceTemplateName, Long referenceTemplateRevision, UUID modelConfigId,
      String modelAlias, String promptText) {
    jdbc.sql(
            """
            INSERT INTO answer_template_generation
              (id, request_key, requested_by, requested_by_name, mode, scenario, requirements,
               reference_template_id, reference_template_name, reference_template_revision,
               model_config_id, model_alias, prompt_text, status)
            VALUES
              (:id, :requestKey, :requestedBy, :requestedByName, :mode, :scenario, :requirements,
               :referenceTemplateId, :referenceTemplateName, :referenceTemplateRevision,
               :modelConfigId, :modelAlias, :promptText, 'QUEUED')
            """)
        .param("id", id)
        .param("requestKey", requestKey)
        .param("requestedBy", requestedBy)
        .param("requestedByName", requestedByName)
        .param("mode", mode)
        .param("scenario", scenario)
        .param("requirements", requirements)
        .param("referenceTemplateId", referenceTemplateId)
        .param("referenceTemplateName", referenceTemplateName)
        .param("referenceTemplateRevision", referenceTemplateRevision)
        .param("modelConfigId", modelConfigId)
        .param("modelAlias", modelAlias)
        .param("promptText", promptText)
        .update();
  }

  /** 同一请求人排队/运行中的任务数，用于并发配额。 */
  public long countActive(UUID requestedBy) {
    return jdbc.sql(
            """
            SELECT count(*) FROM answer_template_generation
            WHERE requested_by = :user AND status IN ('QUEUED', 'RUNNING')
            """)
        .param("user", requestedBy)
        .query(Long.class)
        .single();
  }

  /** 指定时间之后创建的次数，用于每日配额。 */
  public long countCreatedSince(UUID requestedBy, OffsetDateTime since) {
    return jdbc.sql(
            """
            SELECT count(*) FROM answer_template_generation
            WHERE requested_by = :user AND created_at >= :since
            """)
        .param("user", requestedBy)
        .param("since", since)
        .query(Long.class)
        .single();
  }

  /** 开始执行：只有 QUEUED 能进入 RUNNING，重复领取不会重复计费。 */
  public boolean markRunning(UUID id) {
    return jdbc.sql(
            """
            UPDATE answer_template_generation
            SET status = 'RUNNING', started_at = COALESCE(started_at, now()), updated_at = now()
            WHERE id = :id AND status = 'QUEUED'
            """)
        .param("id", id)
        .update() == 1;
  }

  /** 可重试失败：回到 QUEUED，等待任务队列退避后重试。 */
  public void requeue(UUID id) {
    jdbc.sql(
            """
            UPDATE answer_template_generation
            SET status = 'QUEUED', updated_at = now()
            WHERE id = :id AND status = 'RUNNING'
            """)
        .param("id", id)
        .update();
  }

  public boolean succeed(UUID id, String resultBody, Long inputTokens, Long outputTokens) {
    return jdbc.sql(
            """
            UPDATE answer_template_generation
            SET status = 'SUCCEEDED', result_body = :body, input_tokens = :input,
                output_tokens = :output, finished_at = now(), updated_at = now()
            WHERE id = :id AND status = 'RUNNING'
            """)
        .param("id", id)
        .param("body", resultBody)
        .param("input", inputTokens)
        .param("output", outputTokens)
        .update() == 1;
  }

  public boolean fail(UUID id, String failureCode) {
    return jdbc.sql(
            """
            UPDATE answer_template_generation
            SET status = 'FAILED', failure_code = :code, finished_at = now(), updated_at = now()
            WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
            """)
        .param("id", id)
        .param("code", failureCode)
        .update() == 1;
  }
}
