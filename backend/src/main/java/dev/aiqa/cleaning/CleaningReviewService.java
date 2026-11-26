package dev.aiqa.cleaning;

import dev.aiqa.auth.AppUser;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.Digests;
import dev.aiqa.common.ErrorCode;
import dev.aiqa.persistence.IdempotencyRepository;
import dev.aiqa.persistence.JobRepository;
import dev.aiqa.publish.AuditRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 清洗草稿箱的审核与重新打分。
 *
 * <p>审核只接受仍处于「待审核」的条目：批量提交中只要有一条状态已变化就整体回滚，
 * 避免出现「部分通过」后再让管理员猜哪些成功了。
 */
@Service
public class CleaningReviewService {

  private static final String REVIEW_OPERATION = "CLEANING_REVIEW";
  private static final int MAX_ITEMS = 200;
  private static final int MAX_REASON = 2000;
  private static final List<String> FINISHED_STATUSES = List.of("READY", "PARTIAL", "FAILED");

  private final CleaningRepository repository;
  private final IdempotencyRepository idempotency;
  private final AuditRepository audit;
  private final JobRepository jobs;
  private final CleaningModelScorer modelScorer;
  private final TransactionTemplate transactions;

  public CleaningReviewService(
      CleaningRepository repository,
      IdempotencyRepository idempotency,
      AuditRepository audit,
      JobRepository jobs,
      CleaningModelScorer modelScorer,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.idempotency = idempotency;
    this.audit = audit;
    this.jobs = jobs;
    this.modelScorer = modelScorer;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** @param reviewed 实际更新条数 @param items 更新后的条目状态 */
  public record ReviewResult(int reviewed, List<ReviewedItem> items) {}

  /** @param itemId 条目 @param status 更新后的状态 APPROVED/REJECTED */
  public record ReviewedItem(UUID itemId, String status) {}

  /**
   * 批量审核。
   *
   * @param decision APPROVE 或 REJECT；驳回必须填写原因
   */
  @Transactional(propagation = Propagation.NEVER)
  public ReviewResult review(
      AppUser user, List<UUID> itemIds, String decision, String reason, String idempotencyKey) {
    if (!List.of("APPROVE", "REJECT").contains(decision)) {
      throw ApiException.invalid("decision 非法");
    }
    if (itemIds == null || itemIds.isEmpty()) {
      throw ApiException.invalid("至少选择一条条目");
    }
    if (itemIds.size() > MAX_ITEMS) {
      throw ApiException.invalid("单次最多审核 " + MAX_ITEMS + " 条");
    }
    String text = reason == null ? "" : reason.strip();
    if (text.length() > MAX_REASON) {
      throw ApiException.invalid("审核意见最多 2000 个字符");
    }
    if ("REJECT".equals(decision) && text.isEmpty()) {
      throw ApiException.invalid("驳回必须填写具体原因");
    }
    String digest =
        Digests.sha256Hex(
            String.join(",", itemIds.stream().map(UUID::toString).sorted().toList())
                + "|"
                + decision
                + "|"
                + text);
    IdempotencyRepository.Reservation reservation =
        idempotency.reserve(user.id(), REVIEW_OPERATION, idempotencyKey, digest);
    if (reservation.replay()) {
      return currentState(itemIds);
    }
    String status = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
    try {
      ReviewResult result =
          transactions.execute(
              transactionStatus -> {
                int updated = repository.review(itemIds, status, user.id(), text);
                if (updated != itemIds.size()) {
                  throw new ApiException(
                      ErrorCode.STATE_CONFLICT,
                      HttpStatus.CONFLICT,
                      "条目状态已变化，请刷新后重试");
                }
                audit.record(
                    user.id(),
                    "CLEANING_ITEMS_REVIEWED",
                    "cleaning_item",
                    itemIds.get(0),
                    Map.of(
                        "decision", decision,
                        "count", String.valueOf(updated),
                        "reason", text));
                idempotency.complete(
                    user.id(), REVIEW_OPERATION, idempotencyKey, itemIds.get(0), 200);
                return new ReviewResult(
                    updated,
                    itemIds.stream().map(id -> new ReviewedItem(id, status)).toList());
              });
      return result == null ? currentState(itemIds) : result;
    } catch (RuntimeException ex) {
      try {
        idempotency.release(user.id(), REVIEW_OPERATION, idempotencyKey);
      } catch (RuntimeException releaseFailure) {
        ex.addSuppressed(releaseFailure);
      }
      throw ex;
    }
  }

  /** 幂等重放：返回条目当前状态，不重复写审核结果。 */
  private ReviewResult currentState(List<UUID> itemIds) {
    List<ReviewedItem> items = new ArrayList<>();
    for (CleaningRepository.ItemRow row : repository.findItems(itemIds)) {
      items.add(new ReviewedItem(row.id(), row.status()));
    }
    return new ReviewResult(items.size(), List.copyOf(items));
  }

  /**
   * 对仅规则分的条目重新排队打分，用于模型网关恢复后的补偿。
   *
   * @return 重新入队的条目数
   */
  @Transactional(propagation = Propagation.NEVER)
  public int rescore(AppUser user, UUID batchId) {
    if (!modelScorer.enabled()) {
      throw new ApiException(
          ErrorCode.DEPENDENCY_UNAVAILABLE,
          HttpStatus.SERVICE_UNAVAILABLE,
          "当前未启用模型打分");
    }
    CleaningRepository.BatchRow batch =
        repository.findBatch(batchId).orElseThrow(ApiException::notFound);
    if (!FINISHED_STATUSES.contains(batch.status())) {
      throw new ApiException(
          ErrorCode.STATE_CONFLICT, HttpStatus.CONFLICT, "批次仍在处理中，请稍后重试");
    }
    Integer requeued =
        transactions.execute(
            status -> {
              repository.lockBatch(batchId);
              int count = repository.requeueScoring(batchId);
              if (count == 0) {
                throw new ApiException(
                    ErrorCode.STATE_CONFLICT,
                    HttpStatus.CONFLICT,
                    "没有需要重新打分的条目");
              }
              repository.setBatchStatus(batchId, "SCORING");
              jobs.requireCapacity(JobRepository.Kind.CLEAN_SCORE, 1);
              jobs.enqueueCleaningScore(batchId, batch.targetSpaceId());
              return count;
            });
    return requeued == null ? 0 : requeued;
  }
}
