package dev.aiqa.api;

import dev.aiqa.auth.AppUser;
import dev.aiqa.auth.CurrentUser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

  private final CurrentUser current;
  private final JdbcClient jdbc;
  private final dev.aiqa.persistence.IdempotencyRepository idempotency;
  private final dev.aiqa.template.AnswerTemplateRepository templates;
  private final dev.aiqa.settings.ModelConfigRepository configs;

  public SettingsController(
      CurrentUser current,
      JdbcClient jdbc,
      dev.aiqa.persistence.IdempotencyRepository idempotency,
      dev.aiqa.template.AnswerTemplateRepository templates,
      dev.aiqa.settings.ModelConfigRepository configs) {
    this.current = current;
    this.jdbc = jdbc;
    this.idempotency = idempotency;
    this.templates = templates;
    this.configs = configs;
  }

  public record SettingsInput(
      String defaultModelId, String templateId,
      Integer contextLimit, Boolean usageReportEnabled) {}

  /** 契约把 templateId 列为必填（可空）；同时补齐原型需要的上下文上限与用量上报开关。 */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record SettingsView(
      String defaultModelId, String modelAlias, String templateId, String template,
      Integer contextLimit, Boolean usageReportEnabled, long revision) {}

  private long revision() {
    return jdbc.sql("SELECT revision FROM global_setting WHERE singleton_id = 1")
        .query(Long.class).optional().orElse(0L);
  }

  @GetMapping("/global")
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public ResponseEntity<SettingsView> get() {
    current.requireAdmin();
    return ResponseEntity.ok().header(HttpHeaders.ETAG, Http.etag(revision())).body(view());
  }

  private SettingsView view() {
    return jdbc.sql(
            """
            SELECT g.default_model_id, m.alias AS model_alias, g.template_id, t.body AS template,
                   g.context_limit, g.usage_report_enabled, g.revision
            FROM global_setting g
            LEFT JOIN model_config m ON m.id = g.default_model_id
            LEFT JOIN answer_template t ON t.id = g.template_id
            WHERE g.singleton_id = 1
            """)
        .query()
        .listOfRows()
        .stream()
        .findFirst()
        .map(row -> new SettingsView(
            row.get("default_model_id") == null ? null : row.get("default_model_id").toString(),
            (String) row.get("model_alias"),
            row.get("template_id") == null ? null : row.get("template_id").toString(),
            (String) row.get("template"),
            ((Number) row.get("context_limit")).intValue(),
            (Boolean) row.get("usage_report_enabled"),
            ((Number) row.get("revision")).longValue()))
        .orElse(new SettingsView(null, null, null, "", 10, true, 0L));
  }

  @PutMapping("/global")
  public ResponseEntity<SettingsView> put(
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody SettingsInput input) {
    AppUser admin = current.requireAdmin();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new dev.aiqa.common.ApiException(
          dev.aiqa.common.ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
          org.springframework.http.HttpStatus.BAD_REQUEST);
    }
    String digest = dev.aiqa.common.Digests.sha256Hex(
        input.defaultModelId() + "|" + input.templateId() + "|"
            + input.contextLimit() + "|" + input.usageReportEnabled());
    var reservation = idempotency.reserve(admin.id(), "PUT_GLOBAL_SETTINGS", idempotencyKey, digest);
    if (reservation.replay()) {
      return get();
    }
    long expected = Http.requireIfMatch(ifMatch);
    if (revision() != expected) {
      throw dev.aiqa.common.ApiException.preconditionFailed(dev.aiqa.common.ErrorCode.ETAG_MISMATCH);
    }
    java.util.UUID templateId = null;
    if (input.templateId() != null && !input.templateId().isBlank()) {
      templateId = Http.pathUuid(input.templateId(), "templateId");
      templates.find(templateId).orElseThrow(() -> dev.aiqa.common.ApiException.invalid("模板不存在"));
    }
    int contextLimit = input.contextLimit() == null ? 10 : input.contextLimit();
    if (contextLimit < 1 || contextLimit > 100) {
      throw dev.aiqa.common.ApiException.invalid("上下文引用上限必须为 1–100");
    }
    boolean usageReport = input.usageReportEnabled() == null || input.usageReportEnabled();
    java.util.UUID defaultModelId = null;
    if (input.defaultModelId() != null && !input.defaultModelId().isBlank()) {
      defaultModelId = Http.pathUuid(input.defaultModelId(), "defaultModelId");
      dev.aiqa.settings.ModelConfigRepository.ConfigRow model =
          configs.find(defaultModelId).orElseThrow(() -> dev.aiqa.common.ApiException.invalid("默认模型不存在"));
      if (!model.enabled()) {
        throw dev.aiqa.common.ApiException.invalid("默认模型已停用，请先启用或另选");
      }
    }
    int updated = jdbc.sql(
            """
            INSERT INTO global_setting
              (singleton_id, default_model_id, template_id, context_limit,
               usage_report_enabled, updated_by, revision)
            VALUES (1, :model, :template, :limit, :report, :by, 1)
            ON CONFLICT (singleton_id) DO UPDATE
              SET default_model_id = :model, template_id = :template,
                  context_limit = :limit, usage_report_enabled = :report,
                  revision = global_setting.revision + 1, updated_by = :by, updated_at = now()
              WHERE global_setting.revision = :expected
            """)
        .param("model", defaultModelId)
        .param("template", templateId).param("limit", contextLimit)
        .param("report", usageReport).param("by", admin.id()).param("expected", expected).update();
    if (updated == 0) {
      idempotency.release(admin.id(), "PUT_GLOBAL_SETTINGS", idempotencyKey);
      throw dev.aiqa.common.ApiException.preconditionFailed(dev.aiqa.common.ErrorCode.ETAG_MISMATCH);
    }
    idempotency.complete(admin.id(), "PUT_GLOBAL_SETTINGS", idempotencyKey, admin.id(), 200);
    return get();
  }
}
