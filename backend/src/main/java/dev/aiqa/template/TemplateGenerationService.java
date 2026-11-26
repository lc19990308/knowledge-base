package dev.aiqa.template;

import dev.aiqa.auth.AppUser;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.Digests;
import dev.aiqa.common.ErrorCode;
import dev.aiqa.persistence.IdempotencyRepository;
import dev.aiqa.persistence.JobRepository;
import dev.aiqa.publish.AuditRepository;
import dev.aiqa.settings.ModelConfigRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 辅助生成模板：校验、默认模型解析、提示词冻结、入队与幂等。
 * 生成结果只回到生成记录，由管理员确认后再走模板保存流程。
 */
@Service
public class TemplateGenerationService {

  private static final String OPERATION = "CREATE_TEMPLATE_GENERATION";
  private static final String RESOURCE_TYPE = "answer_template_generation";

  private final AnswerTemplateRepository templates;
  private final TemplateGenerationRepository generations;
  private final ModelConfigRepository modelConfigs;
  private final JobRepository jobs;
  private final IdempotencyRepository idempotency;
  private final AuditRepository audit;
  private final int maxActive;
  private final int dailyLimit;

  public TemplateGenerationService(
      AnswerTemplateRepository templates,
      TemplateGenerationRepository generations,
      ModelConfigRepository modelConfigs,
      JobRepository jobs,
      IdempotencyRepository idempotency,
      AuditRepository audit,
      @Value("${aiqa.template.generation.max-active:3}") int maxActive,
      @Value("${aiqa.template.generation.daily-limit:20}") int dailyLimit) {
    this.templates = templates;
    this.generations = generations;
    this.modelConfigs = modelConfigs;
    this.jobs = jobs;
    this.idempotency = idempotency;
    this.audit = audit;
    this.maxActive = maxActive;
    this.dailyLimit = dailyLimit;
  }

  /** 创建请求体；缺省模型时使用全局默认模型。 */
  public record StartRequest(
      String mode, String scenario, String requirements,
      UUID referenceTemplateId, UUID modelConfigId) {}

  /** 对外视图：不暴露 prompt_text 与内部模型配置地址。 */
  public record View(
      UUID generationId, String status, String mode, String scenario, String resultBody,
      String failureCode, Long inputTokens, Long outputTokens, String modelAlias,
      OffsetDateTime createdAt, OffsetDateTime finishedAt) {}

  @Transactional
  public View start(AppUser admin, StartRequest input, String idempotencyKey) {
    String mode = normalizeMode(input.mode());
    String scenario = input.scenario() == null ? "" : input.scenario().strip();
    if (scenario.length() > 240) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_ENTITY,
          "使用场景最多 240 个字符");
    }
    String requirements = input.requirements() == null ? "" : input.requirements().strip();
    if (requirements.length() < 10 || requirements.length() > 2000) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_ENTITY,
          "需求描述需在 10-2000 个字符之间");
    }

    AnswerTemplateRepository.TemplateRow reference = null;
    if (input.referenceTemplateId() != null) {
      reference =
          templates.find(input.referenceTemplateId()).orElseThrow(ApiException::notFound);
    }
    ModelConfigRepository.ConfigRow model = resolveModel(input.modelConfigId());
    requireQuota(admin.id());

    String prompt = TemplatePromptBuilder.build(
        mode, scenario, requirements,
        reference == null ? null : reference.id(),
        reference == null ? "" : reference.name(),
        reference == null ? null : reference.revision(),
        reference == null ? null : reference.body());
    if (prompt.length() > 20000) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_ENTITY,
          "参考模板过长，生成的提示词超过上限");
    }

    String digest = Digests.sha256Hex(
        "template-generation|" + mode + "|" + scenario + "|" + requirements + "|"
            + (reference == null ? "" : reference.id()) + "|" + model.id());
    var reservation = idempotency.reserve(admin.id(), OPERATION, idempotencyKey, digest);
    if (reservation.replay()) {
      return view(findOrThrow(reservation.resourceId()));
    }

    UUID id = UUID.randomUUID();
    try {
      generations.create(
          id, idempotencyKey, admin.id(), admin.displayName(), mode, scenario, requirements,
          reference == null ? null : reference.id(),
          reference == null ? "" : reference.name(),
          reference == null ? null : reference.revision(),
          model.id(), model.alias(), prompt);
      jobs.enqueueGeneration(id);
      audit.record(admin.id(), "TEMPLATE_GENERATION_REQUESTED", RESOURCE_TYPE, id,
          Map.of(
              "mode", mode,
              "modelAlias", model.alias(),
              "hasReference", reference == null ? "false" : "true"));
      idempotency.complete(admin.id(), OPERATION, idempotencyKey, id, 202);
    } catch (RuntimeException ex) {
      idempotency.release(admin.id(), OPERATION, idempotencyKey);
      throw ex;
    }
    return view(findOrThrow(id));
  }

  public View get(AppUser admin, UUID generationId) {
    TemplateGenerationRepository.Row row = findOrThrow(generationId);
    // 生成记录属于提交人；系统管理员可按审计需要查看全部记录。
    if (!row.requestedBy().equals(admin.id()) && !admin.isSystemAdmin()) {
      throw ApiException.forbidden();
    }
    return view(row);
  }

  private TemplateGenerationRepository.Row findOrThrow(UUID id) {
    return generations.find(id).orElseThrow(ApiException::notFound);
  }

  private static View view(TemplateGenerationRepository.Row row) {
    return new View(
        row.id(), row.status(), row.mode(), row.scenario(), row.resultBody(), row.failureCode(),
        row.inputTokens(), row.outputTokens(), row.modelAlias(), row.createdAt(),
        row.finishedAt());
  }

  private static String normalizeMode(String mode) {
    if ("STRUCTURED".equals(mode) || "ADVANCED".equals(mode)) {
      return mode;
    }
    throw new ApiException(ErrorCode.VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_ENTITY,
        "mode 只能是 STRUCTURED 或 ADVANCED");
  }

  /** 显式模型必须可选；缺省时回退全局默认模型，都没有则拒绝。 */
  private ModelConfigRepository.ConfigRow resolveModel(UUID requested) {
    if (requested != null) {
      return modelConfigs.findSelectable(requested).orElseThrow(() -> new ApiException(
          ErrorCode.STATE_CONFLICT, HttpStatus.CONFLICT, "模型不可用或已停用"));
    }
    return modelConfigs.findDefaultConfig()
        .filter(config -> config.enabled())
        .filter(config -> !config.baseUrl().isBlank())
        .filter(config -> !config.modelName().isBlank())
        .orElseThrow(() -> new ApiException(
            ErrorCode.STATE_CONFLICT, HttpStatus.CONFLICT, "尚未配置可用的默认模型"));
  }

  /** 并发与每日配额：保护模型成本。 */
  private void requireQuota(UUID user) {
    if (generations.countActive(user) >= maxActive) {
      throw new ApiException(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
          "同时进行的生成任务已达上限，请等待完成后再试", 5L);
    }
    if (generations.countCreatedSince(user, OffsetDateTime.now().minusDays(1)) >= dailyLimit) {
      throw new ApiException(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
          "24 小时内生成次数已达上限", 3600L);
    }
  }
}
