package dev.aiqa.worker;

import dev.aiqa.persistence.JobRepository;
import dev.aiqa.settings.ModelConfigRepository;
import dev.aiqa.template.TemplateGenerationFailure;
import dev.aiqa.template.TemplateGenerationModel;
import dev.aiqa.template.TemplateGenerationRepository;
import dev.aiqa.template.TemplateOutputValidator;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 模板生成任务：领取任务 → 标记 RUNNING → 调模型 → 清理校验 → 写结果/失败码。
 * 只有可重试的网关错误（超时、不可达、限流）才退回队列，输出不合规则直接失败，避免反复付费。
 */
@Component
public class TemplateGenerationWorker {

  private static final Logger log = LoggerFactory.getLogger(TemplateGenerationWorker.class);

  /** 失败重试上限：首次 + 1 次重试。 */
  private static final int MAX_ATTEMPTS = 2;
  private static final int RETRY_DELAY_SECONDS = 10;
  private static final int LEASE_SECONDS = 180;

  private final JobRepository jobs;
  private final TemplateGenerationRepository generations;
  private final ModelConfigRepository modelConfigs;
  private final TemplateGenerationModel model;
  private final dev.aiqa.publish.AuditRepository audit;
  private final MeterRegistry meters;

  public TemplateGenerationWorker(
      JobRepository jobs,
      TemplateGenerationRepository generations,
      ModelConfigRepository modelConfigs,
      TemplateGenerationModel model,
      dev.aiqa.publish.AuditRepository audit,
      MeterRegistry meters) {
    this.jobs = jobs;
    this.generations = generations;
    this.modelConfigs = modelConfigs;
    this.model = model;
    this.audit = audit;
    this.meters = meters;
  }

  @Scheduled(fixedDelayString = "${aiqa.worker.poll-ms:800}")
  public void poll() {
    try {
      for (int index = 0; index < 2; index++) {
        JobRepository.Job job = jobs.claimNext(JobRepository.Kind.GENERATE_TEMPLATE, LEASE_SECONDS);
        if (job == null) {
          break;
        }
        UUID generationId = job.templateGenerationId();
        if (generationId == null) {
          jobs.fail(job.id(), job.leaseToken(), "INVALID_JOB");
          continue;
        }
        TemplateGenerationRepository.Row row = generations.find(generationId).orElse(null);
        // 已被其他 worker/重试处理过的记录：结束这条重复任务，不重复调用模型。
        if (row == null || !generations.markRunning(generationId)) {
          jobs.succeed(job.id(), job.leaseToken());
          continue;
        }
        run(job, row);
      }
    } catch (Exception ex) {
      log.warn("模板生成任务轮询异常", ex);
    }
  }

  private void run(JobRepository.Job job, TemplateGenerationRepository.Row row) {
    long started = System.nanoTime();
    try {
      ModelConfigRepository.ConfigRow config =
          modelConfigs.find(row.modelConfigId()).orElse(null);
      if (config == null
          || config.baseUrl().isBlank()
          || config.modelName().isBlank()) {
        throw new TemplateGenerationFailure("MODEL_UNAVAILABLE", false, "模型配置已不可用");
      }
      TemplateGenerationModel.Result result = model.generate(config, row.promptText());
      String body = TemplateOutputValidator.validate(result.text());
      if (!generations.succeed(row.id(), body, result.inputTokens(), result.outputTokens())) {
        // 状态已被其他执行改写，直接结束任务，避免双写。
        jobs.succeed(job.id(), job.leaseToken());
        return;
      }
      jobs.succeed(job.id(), job.leaseToken());
      recordMetrics("SUCCEEDED", started, result);
      audit.record(row.requestedBy(), "TEMPLATE_GENERATION_SUCCEEDED", "answer_template_generation",
          row.id(), Map.of("charCount", String.valueOf(body.length()),
              "inputTokens", tokenText(result.inputTokens()),
              "outputTokens", tokenText(result.outputTokens())));
    } catch (TemplateGenerationFailure failure) {
      handleFailure(job, row, failure, started);
    } catch (RuntimeException ex) {
      log.warn("模板生成任务异常 generation={}", row.id(), ex);
      generations.fail(row.id(), "INTERNAL_ERROR");
      jobs.fail(job.id(), job.leaseToken(), "INTERNAL_ERROR");
      recordMetrics("FAILED", started, null);
    }
  }

  /** 可重试且还有次数：退回队列并让生成记录回到 QUEUED；否则落 FAILED。 */
  private void handleFailure(
      JobRepository.Job job, TemplateGenerationRepository.Row row,
      TemplateGenerationFailure failure, long started) {
    boolean canRetry = failure.retryable() && job.attemptCount() < MAX_ATTEMPTS;
    if (canRetry) {
      generations.requeue(row.id());
      jobs.retryLater(job.id(), job.leaseToken(), RETRY_DELAY_SECONDS, failure.code());
      log.info("模板生成将重试 generation={} code={} attempt={}",
          row.id(), failure.code(), job.attemptCount());
      return;
    }
    generations.fail(row.id(), failure.code());
    jobs.fail(job.id(), job.leaseToken(), failure.code());
    audit.record(row.requestedBy(), "TEMPLATE_GENERATION_FAILED", "answer_template_generation",
        row.id(), Map.of("failureCode", failure.code()));
    recordMetrics("FAILED", started, null);
    log.info("模板生成失败 generation={} code={}", row.id(), failure.code());
  }

  /** 未上报 token 用「-」表示，避免审计里出现 "null"。 */
  private static String tokenText(Long value) {
    return value == null ? "-" : String.valueOf(value);
  }

  private void recordMetrics(
      String status, long started, TemplateGenerationModel.Result result) {
    meters.counter("aiqa.template.generation", "status", status).increment();
    meters.timer("aiqa.template.generation.duration", "status", status)
        .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
    if (result != null) {
      if (result.inputTokens() != null) {
        meters.counter("aiqa.template.generation.tokens", "kind", "input")
            .increment(result.inputTokens());
      }
      if (result.outputTokens() != null) {
        meters.counter("aiqa.template.generation.tokens", "kind", "output")
            .increment(result.outputTokens());
      }
      if (result.inputTokens() == null && result.outputTokens() == null) {
        meters.counter("aiqa.model.usage.unreported").increment();
      }
    }
  }
}
