package dev.aiqa.cleaning;

import dev.aiqa.cleaning.CleaningRepository.BatchRow;
import dev.aiqa.cleaning.CleaningRepository.ScoringItem;
import dev.aiqa.persistence.JobRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型打分任务：按批取待打分条目，逐条调用默认模型；整批打完后收尾批次状态。
 *
 * <p>单次领取只处理 {@value #CHUNK} 条，剩余条目通过重新入队继续，避免长时间占住租约。
 * 单条失败只回退该条的规则分；网关整体不可用时整批标记为「仅规则分」，审核不被阻塞。
 */
@Component
public class CleaningScoreWorker {

  private static final Logger log = LoggerFactory.getLogger(CleaningScoreWorker.class);
  private static final int CHUNK = 50;
  private static final int MAX_ATTEMPTS = 3;
  private static final int CLAIM_LIMIT = 2;
  private static final int LEASE_SECONDS = 300;
  private static final double RULE_WEIGHT = 0.4;
  private static final double MODEL_WEIGHT = 0.6;

  private final JobRepository jobs;
  private final CleaningRepository repository;
  private final CleaningModelScorer modelScorer;
  private final TransactionTemplate transactions;
  private final io.micrometer.core.instrument.MeterRegistry meters;
  private final ObjectMapper mapper = new ObjectMapper();

  public CleaningScoreWorker(
      JobRepository jobs,
      CleaningRepository repository,
      CleaningModelScorer modelScorer,
      PlatformTransactionManager transactionManager,
      io.micrometer.core.instrument.MeterRegistry meters) {
    this.jobs = jobs;
    this.repository = repository;
    this.modelScorer = modelScorer;
    this.transactions = new TransactionTemplate(transactionManager);
    this.meters = meters;
  }

  /** 轮询打分任务；单轮最多处理两个批次。 */
  @Scheduled(fixedDelayString = "${aiqa.worker.poll-ms:800}")
  public void poll() {
    try {
      for (int index = 0; index < CLAIM_LIMIT; index++) {
        JobRepository.Job job =
            jobs.claimNext(JobRepository.Kind.CLEAN_SCORE, LEASE_SECONDS);
        if (job == null) {
          return;
        }
        try {
          process(job);
        } catch (RuntimeException ex) {
          log.warn("清洗条目打分失败 job={} batch={}", job.id(), job.cleaningBatchId(), ex);
          handleFailure(job);
        }
      }
    } catch (RuntimeException ex) {
      log.warn("打分任务轮询异常", ex);
    }
  }

  /** 处理一批：模型不可用则整批回退，否则按块打分直到打完。 */
  private void process(JobRepository.Job job) {
    UUID batchId = job.cleaningBatchId();
    if (batchId == null) {
      jobs.fail(job.id(), job.leaseToken(), "CLEANING_BATCH_MISSING");
      return;
    }
    BatchRow batch = repository.findBatch(batchId).orElse(null);
    if (batch == null) {
      jobs.fail(job.id(), job.leaseToken(), "CLEANING_BATCH_MISSING");
      return;
    }
    if (!modelScorer.enabled()) {
      repository.markScoringUnavailable(batchId, extraJson("未启用模型打分，仅规则分"));
      finish(job, batchId);
      return;
    }
    List<ScoringItem> pending = repository.pendingScoring(batchId, CHUNK);
    for (ScoringItem item : pending) {
      Optional<CleaningModelScorer.ModelScore> score =
          modelScorer.score(item.title(), item.body());
      if (score.isPresent()) {
        CleaningModelScorer.ModelScore value = score.get();
        repository.applyModelScore(
            item.id(),
            value.score(),
            weighted(item.ruleScore(), value.score()),
            extraJson(value.reason()));
      } else {
        repository.applyModelScore(
            item.id(), null, item.ruleScore(), extraJson("模型打分失败，已回退规则分"));
      }
    }
    meters.counter("aiqa.cleaning.scored").increment(pending.size());
    if (repository.countPendingScoring(batchId) > 0) {
      jobs.retryLater(job.id(), job.leaseToken(), 0, null);
      return;
    }
    finish(job, batchId);
  }

  /** 收尾：按是否有文件失败决定 READY/PARTIAL，并写完成时间。 */
  private void finish(JobRepository.Job job, UUID batchId) {
    transactions.executeWithoutResult(
        status -> {
          repository.lockBatch(batchId);
          BatchRow fresh = repository.findBatch(batchId).orElseThrow();
          String finalStatus = fresh.failedFileCount() > 0 ? "PARTIAL" : "READY";
          repository.finishBatch(
              batchId,
              finalStatus,
              fresh.itemCount(),
              fresh.duplicateCount(),
              fresh.failedFileCount(),
              true);
          if (!jobs.succeed(job.id(), job.leaseToken())) {
            status.setRollbackOnly();
          }
        });
  }

  /** 重试有限次；用尽后把剩余条目标为仅规则分并收尾，避免草稿箱一直显示「打分中」。 */
  private void handleFailure(JobRepository.Job job) {
    if (job.attemptCount() < MAX_ATTEMPTS) {
      jobs.retryLater(job.id(), job.leaseToken(), 0, "CLEANING_SCORE_FAILED");
      return;
    }
    UUID batchId = job.cleaningBatchId();
    if (batchId != null) {
      repository.markScoringUnavailable(batchId, extraJson("模型打分失败，已回退规则分"));
      finish(job, batchId);
      return;
    }
    jobs.fail(job.id(), job.leaseToken(), "CLEANING_SCORE_FAILED");
  }

  /** 总分按 0.4 规则 + 0.6 模型加权，四舍五入到整数。 */
  static int weighted(int ruleScore, int modelScore) {
    return (int) Math.round(ruleScore * RULE_WEIGHT + modelScore * MODEL_WEIGHT);
  }

  /** 把模型理由包成可并入 score_detail 的 JSON 片段。 */
  private String extraJson(String reason) {
    return mapper.writeValueAsString(Map.of("modelReason", reason));
  }
}
