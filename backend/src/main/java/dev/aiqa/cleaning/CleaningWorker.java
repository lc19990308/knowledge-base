package dev.aiqa.cleaning;

import dev.aiqa.cleaning.CleaningRepository.BatchRow;
import dev.aiqa.cleaning.CleaningRepository.ItemInsert;
import dev.aiqa.cleaning.CleaningRepository.SourceFileRow;
import dev.aiqa.common.Digests;
import dev.aiqa.persistence.JobRepository;
import dev.aiqa.storage.ObjectStore;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 清洗任务：读出原始文件 → 拆条 → 去重 → 规则打分 → 落清洗条目。
 *
 * <p>同一批次串行处理：领取任务后先锁批次行并清掉上一轮产物（重试幂等），
 * 再把条目、来源文件状态与批次状态放在一个事务里提交；启用模型打分时继续投递打分任务。
 */
@Component
public class CleaningWorker {

  private static final Logger log = LoggerFactory.getLogger(CleaningWorker.class);
  private static final int MAX_ATTEMPTS = 3;
  private static final int CLAIM_LIMIT = 3;
  private static final int LEASE_SECONDS = 300;
  private static final String FAILURE_CODE = "CLEANING_FAILED";

  private final JobRepository jobs;
  private final CleaningRepository repository;
  private final ObjectStore objects;
  private final RawDatasetReader reader;
  private final ItemSplitter splitter;
  private final RuleScorer scorer;
  private final TransactionTemplate transactions;
  private final io.micrometer.core.instrument.MeterRegistry meters;
  private final ObjectMapper mapper = new ObjectMapper();

  public CleaningWorker(
      JobRepository jobs,
      CleaningRepository repository,
      ObjectStore objects,
      RawDatasetReader reader,
      ItemSplitter splitter,
      RuleScorer scorer,
      PlatformTransactionManager transactionManager,
      io.micrometer.core.instrument.MeterRegistry meters) {
    this.jobs = jobs;
    this.repository = repository;
    this.objects = objects;
    this.reader = reader;
    this.splitter = splitter;
    this.scorer = scorer;
    this.transactions = new TransactionTemplate(transactionManager);
    this.meters = meters;
  }

  /** 轮询清洗任务；单轮最多处理 {@value #CLAIM_LIMIT} 个，避免一个实例独占队列。 */
  @Scheduled(fixedDelayString = "${aiqa.worker.poll-ms:800}")
  public void poll() {
    try {
      for (int index = 0; index < CLAIM_LIMIT; index++) {
        JobRepository.Job job = jobs.claimNext(JobRepository.Kind.CLEAN, LEASE_SECONDS);
        if (job == null) {
          return;
        }
        try {
          process(job);
        } catch (RuntimeException ex) {
          log.warn("清洗批次失败 job={} batch={}", job.id(), job.cleaningBatchId(), ex);
          handleFailure(job);
        }
      }
    } catch (RuntimeException ex) {
      log.warn("清洗任务轮询异常", ex);
    }
  }

  /** 单个批次：已提交过结果则直接收尾，否则重跑并提交。 */
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
    // 上一轮已提交但没来得及标记成功：不再重复产出，直接收尾。
    if (repository.itemCount(batchId) > 0) {
      jobs.succeed(job.id(), job.leaseToken());
      return;
    }
    List<SourceFileRow> files = prepare(batchId);
    Run run = extract(batch, files);
    commit(job, batch, run);
  }

  /** 事务内锁批次、清空上一轮产物并读出要处理的原件清单。 */
  private List<SourceFileRow> prepare(UUID batchId) {
    List<SourceFileRow> files =
        transactions.execute(
            status -> {
              repository.lockBatch(batchId);
              repository.resetForReprocess(batchId);
              return repository.sourceFiles(batchId);
            });
    return files == null ? List.of() : files;
  }

  /** @param items 待插入条目 @param sourceStates 来源文件 → 处理结果 */
  private record Run(
      List<ItemInsert> items, Map<UUID, SourceState> sourceStates, int duplicates, int failed) {}

  /** @param state READY/FAILED @param recordCount 产出记录数 @param errorCode 失败原因码 */
  private record SourceState(String state, int recordCount, String errorCode) {}

  /** 逐文件读取与拆分；单个文件失败只影响该文件，批次继续。 */
  private Run extract(BatchRow batch, List<SourceFileRow> files) {
    List<ItemInsert> items = new ArrayList<>();
    Map<UUID, SourceState> sourceStates = new LinkedHashMap<>();
    Set<String> digests = new HashSet<>();
    int ordinal = 0;
    int duplicates = 0;
    int failed = 0;
    for (SourceFileRow file : files) {
      int records = 0;
      try {
        byte[] content = readObject(file.objectKey());
        for (RawRecord record : reader.read(file.relativePath(), content)) {
          for (ItemSplitter.Candidate candidate : splitter.split(record)) {
            records++;
            String digest = Digests.sha256Hex(digestText(candidate.body()));
            if (!digests.add(digest)) {
              duplicates++;
              continue;
            }
            RuleScorer.RuleScore score =
                scorer.score(candidate.title(), candidate.body(), candidate.rawExcerpt());
            items.add(
                new ItemInsert(
                    UUID.randomUUID(),
                    batch.id(),
                    file.id(),
                    ordinal++,
                    candidate.title(),
                    candidate.body(),
                    digest,
                    candidate.rawExcerpt(),
                    candidate.location(),
                    score.total(),
                    score.total(),
                    detailJson(score, batch.modelScoring()),
                    batch.modelScoring() ? "PENDING" : "UNAVAILABLE"));
          }
        }
        sourceStates.put(file.id(), new SourceState("READY", records, null));
      } catch (RawReadException ex) {
        failed++;
        sourceStates.put(file.id(), new SourceState("FAILED", records, ex.code()));
      }
    }
    return new Run(items, sourceStates, duplicates, failed);
  }

  /** 事务内写入条目与来源状态，并按是否需要模型打分决定批次状态与后续任务。 */
  private void commit(JobRepository.Job job, BatchRow batch, Run run) {
    Boolean committed =
        transactions.execute(
            status -> {
              repository.lockBatch(batch.id());
              if (repository.itemCount(batch.id()) > 0) {
                jobs.succeed(job.id(), job.leaseToken());
                return Boolean.TRUE;
              }
              repository.insertItems(run.items());
              for (Map.Entry<UUID, SourceState> entry : run.sourceStates().entrySet()) {
                SourceState source = entry.getValue();
                repository.markSourceFile(
                    entry.getKey(), source.state(), source.recordCount(), source.errorCode());
              }
              String finalStatus =
                  batch.modelScoring()
                      ? "SCORING"
                      : run.failed() > 0 ? "PARTIAL" : "READY";
              repository.finishBatch(
                  batch.id(),
                  finalStatus,
                  run.items().size(),
                  run.duplicates(),
                  run.failed(),
                  !batch.modelScoring());
              if (batch.modelScoring()) {
                jobs.requireCapacity(JobRepository.Kind.CLEAN_SCORE, 1);
                jobs.enqueueCleaningScore(batch.id(), batch.targetSpaceId());
              }
              if (!jobs.succeed(job.id(), job.leaseToken())) {
                status.setRollbackOnly();
                return Boolean.FALSE;
              }
              return Boolean.TRUE;
            });
    if (Boolean.TRUE.equals(committed)) {
      meters.counter("aiqa.cleaning.items").increment(run.items().size());
    }
  }

  /** 重试有限次；用尽后把批次标为失败，避免一直排队。 */
  private void handleFailure(JobRepository.Job job) {
    UUID batchId = job.cleaningBatchId();
    if (job.attemptCount() < MAX_ATTEMPTS) {
      jobs.retryLater(job.id(), job.leaseToken(), 5 * job.attemptCount(), FAILURE_CODE);
      return;
    }
    jobs.fail(job.id(), job.leaseToken(), FAILURE_CODE);
    if (batchId != null) {
      transactions.executeWithoutResult(
          status -> {
            repository.lockBatch(batchId);
            repository.failBatch(batchId, FAILURE_CODE);
          });
    }
    meters.counter("aiqa.cleaning.failures").increment();
  }

  /** 读取对象存储内容；失败按文件级错误处理，不中断整批。 */
  private byte[] readObject(String key) {
    try (InputStream input = objects.get(key)) {
      return input.readAllBytes();
    } catch (IOException ex) {
      throw new RawReadException("EXTRACT_FAILED", "原件读取失败");
    }
  }

  /** 去重口径：忽略大小写、空白与全部标点符号后的正文。 */
  static String digestText(String body) {
    return body.strip().toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
  }

  /** 评分明细落库：规则各维度 + 未启用模型打分时的说明。 */
  private String detailJson(RuleScorer.RuleScore score, boolean modelScoring) {
    Map<String, Object> detail = new LinkedHashMap<>(score.detail());
    if (!modelScoring) {
      detail.put("modelReason", "未启用模型打分，仅规则分");
    }
    return mapper.writeValueAsString(detail);
  }
}
