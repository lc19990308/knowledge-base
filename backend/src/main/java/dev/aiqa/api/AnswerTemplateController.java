package dev.aiqa.api;

import dev.aiqa.auth.AppUser;
import dev.aiqa.auth.CurrentUser;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import dev.aiqa.common.PageQuery;
import dev.aiqa.common.PageView;
import dev.aiqa.template.AnswerTemplateRepository;
import dev.aiqa.template.TemplateGenerationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Answer templates: system administrators only, because templates shape every answer. */
@RestController
@RequestMapping("/api/v1/answer-templates")
public class AnswerTemplateController {

  private final CurrentUser current;
  private final AnswerTemplateRepository templates;
  private final dev.aiqa.assistant.AssistantRepository assistants;
  private final dev.aiqa.publish.AuditRepository audit;
  private final dev.aiqa.persistence.IdempotencyRepository idempotency;
  private final TemplateGenerationService generations;

  public AnswerTemplateController(
      CurrentUser current,
      AnswerTemplateRepository templates,
      dev.aiqa.assistant.AssistantRepository assistants,
      dev.aiqa.publish.AuditRepository audit,
      dev.aiqa.persistence.IdempotencyRepository idempotency,
      TemplateGenerationService generations) {
    this.current = current;
    this.templates = templates;
    this.assistants = assistants;
    this.audit = audit;
    this.idempotency = idempotency;
    this.generations = generations;
  }

  public record TemplateInput(String name, String body, Boolean enabled) {}

  /** 生成请求：mode 为 STRUCTURED / ADVANCED，referenceTemplateId 与 modelConfigId 可选。 */
  public record GenerationInput(
      String mode, String scenario, String requirements,
      UUID referenceTemplateId, UUID modelConfigId) {}

  public record TemplateView(
      UUID templateId, String name, String body, boolean enabled, long revision,
      boolean isGlobal, List<UUID> assistantIds, String updatedAt) {}

  private TemplateView view(AnswerTemplateRepository.TemplateRow row) {
    UUID globalId = templates.globalTemplateId().orElse(null);
    return new TemplateView(
        row.id(), row.name(), row.body(), row.enabled(), row.revision(),
        row.id().equals(globalId), templates.assistantIdsUsing(row.id()),
        row.updatedAt() == null ? null : row.updatedAt().toString());
  }

  @GetMapping
  public PageView<TemplateView> list(
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {
    current.requireAdmin();
    PageQuery page = PageQuery.of(limit, offset);
    List<AnswerTemplateRepository.TemplateRow> rows = templates.list();
    return PageView.of(page.slice(rows).stream().map(this::view).toList(), rows.size());
  }

  @GetMapping("/{templateId}")
  public ResponseEntity<TemplateView> get(@PathVariable String templateId) {
    current.requireAdmin();
    UUID id = Http.pathUuid(templateId, "templateId");
    AnswerTemplateRepository.TemplateRow row =
        templates.find(id).orElseThrow(ApiException::notFound);
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, Http.etag(row.revision()))
        .body(view(row));
  }

  @PostMapping
  public ResponseEntity<TemplateView> create(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody TemplateInput input) {
    AppUser admin = current.requireAdmin();
    requireIdempotency(idempotencyKey);
    String name = requireText(input.name(), "模板名称不能为空");
    String body = requireText(input.body(), "模板正文不能为空");
    String digest = dev.aiqa.common.Digests.sha256Hex("create|" + name + "|" + body);
    var reservation = idempotency.reserve(admin.id(), "CREATE_ANSWER_TEMPLATE", idempotencyKey, digest);
    if (reservation.replay()) {
      AnswerTemplateRepository.TemplateRow existing =
          templates.find(reservation.resourceId()).orElseThrow(ApiException::notFound);
      return ResponseEntity.status(HttpStatus.CREATED)
          .header(HttpHeaders.LOCATION, "/api/v1/answer-templates/" + existing.id())
          .header(HttpHeaders.ETAG, Http.etag(existing.revision()))
          .body(view(existing));
    }
    UUID id;
    try {
      id = templates.create(name, body);
      audit.record(admin.id(), "ANSWER_TEMPLATE_CREATED", "answer_template", id,
          Map.of("name", name));
      idempotency.complete(admin.id(), "CREATE_ANSWER_TEMPLATE", idempotencyKey, id, 201);
    } catch (RuntimeException ex) {
      idempotency.release(admin.id(), "CREATE_ANSWER_TEMPLATE", idempotencyKey);
      throw ex;
    }
    AnswerTemplateRepository.TemplateRow created =
        templates.find(id).orElseThrow(ApiException::notFound);
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.LOCATION, "/api/v1/answer-templates/" + id)
        .header(HttpHeaders.ETAG, Http.etag(created.revision()))
        .body(view(created));
  }

  @PutMapping("/{templateId}")
  public ResponseEntity<TemplateView> update(
      @PathVariable String templateId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody TemplateInput input) {
    AppUser admin = current.requireAdmin();
    requireIdempotency(idempotencyKey);
    UUID id = Http.pathUuid(templateId, "templateId");
    long expected = Http.requireIfMatch(ifMatch);
    AnswerTemplateRepository.TemplateRow existing =
        templates.find(id).orElseThrow(ApiException::notFound);
    // A stale If-Match is a precondition failure (412), not a state conflict.
    if (existing.revision() != expected) {
      throw ApiException.preconditionFailed(ErrorCode.ETAG_MISMATCH);
    }
    String name = requireText(input.name(), "模板名称不能为空");
    String body = requireText(input.body(), "模板正文不能为空");
    String digest = dev.aiqa.common.Digests.sha256Hex(
        "update|" + id + "|" + expected + "|" + name + "|" + body + "|" + input.enabled());
    var reservation = idempotency.reserve(admin.id(), "UPDATE_ANSWER_TEMPLATE", idempotencyKey, digest);
    if (reservation.replay()) {
      AnswerTemplateRepository.TemplateRow replay =
          templates.find(id).orElseThrow(ApiException::notFound);
      return ResponseEntity.ok()
          .header(HttpHeaders.ETAG, Http.etag(replay.revision()))
          .body(view(replay));
    }
    try {
      templates.update(id, name, body, input.enabled(), expected);
      audit.record(admin.id(), "ANSWER_TEMPLATE_UPDATED", "answer_template", id,
          Map.of("name", name, "enabled", String.valueOf(input.enabled() == null || input.enabled())));
      idempotency.complete(admin.id(), "UPDATE_ANSWER_TEMPLATE", idempotencyKey, id, 200);
    } catch (RuntimeException ex) {
      idempotency.release(admin.id(), "UPDATE_ANSWER_TEMPLATE", idempotencyKey);
      throw ex;
    }
    AnswerTemplateRepository.TemplateRow updated =
        templates.find(id).orElseThrow(ApiException::notFound);
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, Http.etag(updated.revision()))
        .body(view(updated));
  }

  @DeleteMapping("/{templateId}")
  public ResponseEntity<Void> delete(
      @PathVariable String templateId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    AppUser admin = current.requireAdmin();
    UUID id = Http.pathUuid(templateId, "templateId");
    long expected = Http.requireIfMatch(ifMatch);
    templates.find(id).orElseThrow(ApiException::notFound);
    if (templates.globalTemplateId().filter(id::equals).isPresent()) {
      throw ApiException.conflict(ErrorCode.STATE_CONFLICT);
    }
    List<UUID> inUse = templates.assistantIdsUsing(id);
    if (!inUse.isEmpty()) {
      throw ApiException.conflict(ErrorCode.STATE_CONFLICT);
    }
    templates.delete(id, expected);
    audit.record(admin.id(), "ANSWER_TEMPLATE_DELETED", "answer_template", id, Map.of());
    return ResponseEntity.noContent().build();
  }

  private static String requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw ApiException.invalid(message);
    }
    return value.strip();
  }

  private static void requireIdempotency(String key) {
    if (key == null || key.isBlank()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
    }
  }

  @PostMapping("/generations")
  public ResponseEntity<TemplateGenerationService.View> startGeneration(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody GenerationInput input) {
    AppUser admin = current.requireAdmin();
    TemplateGenerationService.View created = generations.start(
        admin,
        new TemplateGenerationService.StartRequest(
            input.mode(), input.scenario(), input.requirements(),
            input.referenceTemplateId(), input.modelConfigId()),
        idempotencyKey);
    return ResponseEntity.accepted()
        .header(HttpHeaders.LOCATION, "/api/v1/answer-templates/generations/" + created.generationId())
        .body(created);
  }

  @GetMapping("/generations/{generationId}")
  public TemplateGenerationService.View getGeneration(@PathVariable String generationId) {
    AppUser admin = current.requireAdmin();
    return generations.get(admin, Http.pathUuid(generationId, "generationId"));
  }
}
