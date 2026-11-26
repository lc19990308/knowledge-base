package dev.aiqa.cleaning;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 清洗批次的持久化访问。
 *
 * <p>写操作都按批次串行：worker 先 {@link #lockBatch} 再改状态与条目，避免同一批次被并发重跑。
 * 列表查询按筛选条件动态拼 SQL，分页与总数使用同一份条件，保证 total 与当前页口径一致。
 */
@Repository
public class CleaningRepository {

  private final JdbcClient jdbc;

  public CleaningRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 批次行；averageScore 由子查询算出，可空。 */
  public record BatchRow(
      UUID id, String name, UUID targetSpaceId, UUID createdBy, String status,
      boolean modelScoring, int fileCount, int skippedCount, int failedFileCount,
      int itemCount, int duplicateCount, Integer averageScore, String errorCode,
      OffsetDateTime createdAt, OffsetDateTime finishedAt) {}

  /** 来源文件行；objectKey 只在清洗时读取，不返回给接口。 */
  public record SourceFileRow(
      UUID id, UUID batchId, int ordinal, String relativePath, String displayName,
      String mediaType, long sizeBytes, String sha256, String objectKey,
      String state, int recordCount, String errorCode) {}

  /** 条目列表行；targetSpaceId 取「已流转的空间」或「批次预选空间」。 */
  public record ItemRow(
      UUID id, UUID batchId, String batchName, String title, String excerpt,
      String sourcePath, int ruleScore, Integer modelScore, int totalScore,
      String scoreState, String modelReason, String status,
      UUID targetSpaceId, String targetSpaceName, UUID promotedDocumentVersionId,
      OffsetDateTime createdAt, OffsetDateTime reviewedAt, String reviewReason) {}

  /** 条目详情：在列表行之外补充正文、原文摘录与评分明细。 */
  public record ItemDetailRow(
      UUID id, UUID batchId, String batchName, String title, String excerpt,
      String sourcePath, int ruleScore, Integer modelScore, int totalScore,
      String scoreState, String modelReason, String status,
      UUID targetSpaceId, String targetSpaceName, UUID promotedDocumentVersionId,
      OffsetDateTime createdAt, OffsetDateTime reviewedAt, String reviewReason,
      String body, String rawExcerpt, String scoreDetailJson, String reviewedBy,
      UUID promotedSpaceId, OffsetDateTime promotedAt) {}

  /** 待入库条目；流转时只需要这些字段拼文档。 */
  public record PromotionItem(
      UUID id, UUID batchId, String batchName, String title, String body, String status,
      UUID promotedDocumentVersionId) {}

  /** 待打分的条目。 */
  public record ScoringItem(UUID id, String title, String body, int ruleScore) {}

  /** 新条目插入参数。 */
  public record ItemInsert(
      UUID id, UUID batchId, UUID sourceFileId, int ordinal, String title, String body,
      String bodyDigest, String rawExcerpt, String sourceLocation, int ruleScore,
      int totalScore, String scoreDetailJson, String scoreState) {}

  // ---------- 批次 ----------

  public void createBatch(BatchRow row) {
    jdbc.sql(
            """
            INSERT INTO cleaning_batch
              (id, name, target_space_id, created_by, status, model_scoring, file_count,
               skipped_count, failed_file_count, item_count, duplicate_count)
            VALUES (:id, :name, :targetSpace, :createdBy, :status, :modelScoring, :fileCount,
                    :skippedCount, :failedFileCount, 0, 0)
            """)
        .param("id", row.id())
        .param("name", row.name())
        .param("targetSpace", row.targetSpaceId())
        .param("createdBy", row.createdBy())
        .param("status", row.status())
        .param("modelScoring", row.modelScoring())
        .param("fileCount", row.fileCount())
        .param("skippedCount", row.skippedCount())
        .param("failedFileCount", row.failedFileCount())
        .update();
  }

  public void insertSourceFiles(List<SourceFileRow> files) {
    for (SourceFileRow file : files) {
      jdbc.sql(
              """
              INSERT INTO cleaning_source_file
                (id, batch_id, ordinal, relative_path, display_name, media_type, size_bytes,
                 sha256, object_key, state)
              VALUES (:id, :batch, :ordinal, :path, :name, :mediaType, :size, :sha, :key, 'PENDING')
              """)
          .param("id", file.id())
          .param("batch", file.batchId())
          .param("ordinal", file.ordinal())
          .param("path", file.relativePath())
          .param("name", file.displayName())
          .param("mediaType", file.mediaType())
          .param("size", file.sizeBytes())
          .param("sha", file.sha256())
          .param("key", file.objectKey())
          .update();
    }
  }

  private static final String BATCH_COLUMNS =
      """
      SELECT b.id, b.name, b.target_space_id, b.created_by, b.status, b.model_scoring,
             b.file_count, b.skipped_count, b.failed_file_count, b.item_count,
             b.duplicate_count, b.error_code, b.created_at, b.finished_at,
             (SELECT round(avg(i.total_score))::int FROM cleaning_item i WHERE i.batch_id = b.id)
               AS average_score
      FROM cleaning_batch b
      """;

  public Optional<BatchRow> findBatch(UUID batchId) {
    return jdbc.sql(BATCH_COLUMNS + " WHERE b.id = :id")
        .param("id", batchId)
        .query(BatchRow.class)
        .optional();
  }

  public List<BatchRow> listBatches(String status, Integer limit, int offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = "";
    if (status != null && !status.isBlank()) {
      where = " WHERE b.status = :status";
      params.put("status", status);
    }
    params.put("offset", offset);
    String paging = " OFFSET :offset";
    if (limit != null) {
      paging = " LIMIT :limit OFFSET :offset";
      params.put("limit", limit);
    }
    return jdbc.sql(BATCH_COLUMNS + where + " ORDER BY b.created_at DESC, b.id" + paging)
        .params(params)
        .query(BatchRow.class)
        .list();
  }

  public long countBatches(String status) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = "";
    if (status != null && !status.isBlank()) {
      where = " WHERE status = :status";
      params.put("status", status);
    }
    return jdbc.sql("SELECT count(*) FROM cleaning_batch" + where)
        .params(params)
        .query(Long.class)
        .single();
  }

  /** 锁住批次行，供 worker 串行处理同一批次。 */
  public void lockBatch(UUID batchId) {
    jdbc.sql("SELECT id FROM cleaning_batch WHERE id = :id FOR UPDATE")
        .param("id", batchId)
        .query(UUID.class)
        .optional();
  }

  public void setBatchStatus(UUID batchId, String status) {
    jdbc.sql("UPDATE cleaning_batch SET status = :status WHERE id = :id")
        .param("status", status)
        .param("id", batchId)
        .update();
  }

  /** 批次已产出的条目数；用于判断上一轮是否已经提交过结果。 */
  public long itemCount(UUID batchId) {
    return jdbc.sql("SELECT count(*) FROM cleaning_item WHERE batch_id = :batch")
        .param("batch", batchId)
        .query(Long.class)
        .single();
  }

  /** 重跑前清空上一轮产物，保证重试幂等。 */
  public void resetForReprocess(UUID batchId) {
    jdbc.sql("DELETE FROM cleaning_item WHERE batch_id = :id").param("id", batchId).update();
    jdbc.sql(
            """
            UPDATE cleaning_source_file
            SET state = 'PENDING', record_count = 0, error_code = NULL
            WHERE batch_id = :id
            """)
        .param("id", batchId)
        .update();
    jdbc.sql(
            """
            UPDATE cleaning_batch
            SET status = 'EXTRACTING', item_count = 0, duplicate_count = 0,
                failed_file_count = 0, error_code = NULL, finished_at = NULL
            WHERE id = :id
            """)
        .param("id", batchId)
        .update();
  }

  public void markSourceFile(UUID sourceFileId, String state, int recordCount, String errorCode) {
    jdbc.sql(
            """
            UPDATE cleaning_source_file
            SET state = :state, record_count = :records, error_code = :error
            WHERE id = :id
            """)
        .param("state", state)
        .param("records", recordCount)
        .param("error", errorCode)
        .param("id", sourceFileId)
        .update();
  }

  /** 收尾：写回计数与最终状态；finished 为 true 时同时写完成时间。 */
  public void finishBatch(
      UUID batchId, String status, int itemCount, int duplicateCount, int failedFileCount,
      boolean finished) {
    jdbc.sql(
            """
            UPDATE cleaning_batch
            SET status = :status, item_count = :items, duplicate_count = :duplicates,
                failed_file_count = :failed,
                finished_at = CASE WHEN :finished THEN now() ELSE finished_at END
            WHERE id = :id
            """)
        .param("status", status)
        .param("items", itemCount)
        .param("duplicates", duplicateCount)
        .param("failed", failedFileCount)
        .param("finished", finished)
        .param("id", batchId)
        .update();
  }

  public void failBatch(UUID batchId, String errorCode) {
    jdbc.sql(
            """
            UPDATE cleaning_batch
            SET status = 'FAILED', error_code = :error, finished_at = now()
            WHERE id = :id
            """)
        .param("error", errorCode)
        .param("id", batchId)
        .update();
  }

  public List<SourceFileRow> sourceFiles(UUID batchId) {
    return jdbc.sql(
            """
            SELECT id, batch_id, ordinal, relative_path, display_name, media_type, size_bytes,
                   sha256, object_key, state, record_count, error_code
            FROM cleaning_source_file
            WHERE batch_id = :id
            ORDER BY ordinal
            """)
        .param("id", batchId)
        .query(SourceFileRow.class)
        .list();
  }

  public UUID batchTargetSpace(UUID batchId) {
    return jdbc.sql("SELECT target_space_id FROM cleaning_batch WHERE id = :id")
        .param("id", batchId)
        .query(UUID.class)
        .optional()
        .orElse(null);
  }

  // ---------- 条目 ----------

  public void insertItems(List<ItemInsert> items) {
    for (ItemInsert item : items) {
      jdbc.sql(
              """
              INSERT INTO cleaning_item
                (id, batch_id, source_file_id, ordinal, title, body, body_digest, raw_excerpt,
                 source_location, rule_score, model_score, total_score, score_detail, score_state,
                 status)
              VALUES (:id, :batch, :source, :ordinal, :title, :body, :digest, :excerpt,
                      :location, :rule, NULL, :total, CAST(:detail AS jsonb), :state, 'PENDING_REVIEW')
              ON CONFLICT (batch_id, body_digest) DO NOTHING
              """)
          .param("id", item.id())
          .param("batch", item.batchId())
          .param("source", item.sourceFileId())
          .param("ordinal", item.ordinal())
          .param("title", item.title())
          .param("body", item.body())
          .param("digest", item.bodyDigest())
          .param("excerpt", item.rawExcerpt())
          .param("location", item.sourceLocation())
          .param("rule", item.ruleScore())
          .param("total", item.totalScore())
          .param("detail", item.scoreDetailJson())
          .param("state", item.scoreState())
          .update();
    }
  }

  private record ItemQuery(String where, Map<String, Object> params) {}

  /** 条目筛选条件；列表与计数共用，避免两处口径不一致。 */
  private ItemQuery itemQuery(
      String status, UUID batchId, UUID spaceId, Integer minScore, String query) {
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    Map<String, Object> params = new LinkedHashMap<>();
    if (status != null && !status.isBlank()) {
      where.append(" AND i.status = :status");
      params.put("status", status);
    }
    if (batchId != null) {
      where.append(" AND i.batch_id = :batch");
      params.put("batch", batchId);
    }
    if (spaceId != null) {
      where.append(" AND COALESCE(i.promoted_space_id, b.target_space_id) = :space");
      params.put("space", spaceId);
    }
    if (minScore != null && minScore > 0) {
      where.append(" AND i.total_score >= :minScore");
      params.put("minScore", minScore);
    }
    if (query != null && !query.isBlank()) {
      where.append(" AND (i.title ILIKE :q OR i.body ILIKE :q)");
      params.put("q", "%" + query.strip() + "%");
    }
    return new ItemQuery(where.toString(), params);
  }

  private static final String ITEM_COLUMNS =
      """
      SELECT i.id, i.batch_id, b.name AS batch_name, i.title, left(i.body, 160) AS excerpt,
             i.source_location AS source_path, i.rule_score, i.model_score, i.total_score,
             i.score_state, i.score_detail ->> 'modelReason' AS model_reason, i.status,
             COALESCE(i.promoted_space_id, b.target_space_id) AS target_space_id,
             s.name AS target_space_name, i.promoted_document_version_id, i.created_at,
             i.reviewed_at, i.review_reason
      FROM cleaning_item i
      JOIN cleaning_batch b ON b.id = i.batch_id
      LEFT JOIN space s ON s.id = COALESCE(i.promoted_space_id, b.target_space_id)
      """;

  public List<ItemRow> listItems(
      String status, UUID batchId, UUID spaceId, Integer minScore, String query, Integer limit,
      int offset) {
    ItemQuery filter = itemQuery(status, batchId, spaceId, minScore, query);
    Map<String, Object> params = new LinkedHashMap<>(filter.params());
    params.put("offset", offset);
    String paging = " OFFSET :offset";
    if (limit != null) {
      paging = " LIMIT :limit OFFSET :offset";
      params.put("limit", limit);
    }
    return jdbc.sql(
            ITEM_COLUMNS
                + filter.where()
                + " ORDER BY i.total_score DESC, i.created_at DESC, i.id"
                + paging)
        .params(params)
        .query(ItemRow.class)
        .list();
  }

  public long countItems(
      String status, UUID batchId, UUID spaceId, Integer minScore, String query) {
    ItemQuery filter = itemQuery(status, batchId, spaceId, minScore, query);
    return jdbc.sql(
            "SELECT count(*) FROM cleaning_item i JOIN cleaning_batch b ON b.id = i.batch_id"
                + filter.where())
        .params(filter.params())
        .query(Long.class)
        .single();
  }

  public Optional<ItemDetailRow> findItem(UUID itemId) {
    return jdbc.sql(
            """
            SELECT i.id, i.batch_id, b.name AS batch_name, i.title, left(i.body, 160) AS excerpt,
                   i.source_location AS source_path, i.rule_score, i.model_score, i.total_score,
                   i.score_state, i.score_detail ->> 'modelReason' AS model_reason, i.status,
                   COALESCE(i.promoted_space_id, b.target_space_id) AS target_space_id,
                   s.name AS target_space_name, i.promoted_document_version_id, i.created_at,
                   i.reviewed_at, i.review_reason, i.body, i.raw_excerpt,
                   CAST(i.score_detail AS text) AS score_detail_json,
                   u.display_name AS reviewed_by, i.promoted_space_id, i.promoted_at
            FROM cleaning_item i
            JOIN cleaning_batch b ON b.id = i.batch_id
            LEFT JOIN space s ON s.id = COALESCE(i.promoted_space_id, b.target_space_id)
            LEFT JOIN app_user u ON u.id = i.reviewed_by
            WHERE i.id = :id
            """)
        .param("id", itemId)
        .query(ItemDetailRow.class)
        .optional();
  }

  /** 批量读取条目状态，审核前校验用；不返回正文。 */
  public List<ItemRow> findItems(List<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(ITEM_COLUMNS + " WHERE i.id IN (:ids)")
        .param("ids", ids)
        .query(ItemRow.class)
        .list();
  }

  /** 流转前读取条目正文；同时带出批次名用于生成文档标题。 */
  public List<PromotionItem> promotionItems(List<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT i.id, i.batch_id, b.name AS batch_name, i.title, i.body, i.status,
                   i.promoted_document_version_id
            FROM cleaning_item i
            JOIN cleaning_batch b ON b.id = i.batch_id
            WHERE i.id IN (:ids)
            ORDER BY i.ordinal
            """)
        .param("ids", ids)
        .query(PromotionItem.class)
        .list();
  }

  /** 审核：只更新仍处于待审核的条目，返回实际更新行数供调用方校验。 */
  public int review(List<UUID> ids, String status, UUID reviewer, String reason) {
    return jdbc.sql(
            """
            UPDATE cleaning_item
            SET status = :status, reviewed_by = :reviewer, reviewed_at = now(), review_reason = :reason
            WHERE id IN (:ids) AND status = 'PENDING_REVIEW'
            """)
        .param("status", status)
        .param("reviewer", reviewer)
        .param("reason", reason)
        .param("ids", ids)
        .update();
  }

  public List<ScoringItem> pendingScoring(UUID batchId, int limit) {
    return jdbc.sql(
            """
            SELECT id, title, body, rule_score
            FROM cleaning_item
            WHERE batch_id = :batch AND score_state = 'PENDING'
            ORDER BY ordinal
            LIMIT :limit
            """)
        .param("batch", batchId)
        .param("limit", limit)
        .query(ScoringItem.class)
        .list();
  }

  public long countPendingScoring(UUID batchId) {
    return jdbc.sql(
            "SELECT count(*) FROM cleaning_item WHERE batch_id = :batch AND score_state = 'PENDING'")
        .param("batch", batchId)
        .query(Long.class)
        .single();
  }

  /** 写入模型分与总分，并把模型理由（extraJson）并进评分明细。 */
  public void applyModelScore(
      UUID itemId, Integer modelScore, int totalScore, String extraJson) {
    jdbc.sql(
            """
            UPDATE cleaning_item
            SET model_score = :model, total_score = :total,
                score_state = CASE WHEN :model IS NULL THEN 'UNAVAILABLE' ELSE 'SCORED' END,
                score_detail = score_detail || CAST(:extra AS jsonb)
            WHERE id = :id AND score_state = 'PENDING'
            """)
        .param("model", modelScore)
        .param("total", totalScore)
        .param("extra", extraJson)
        .param("id", itemId)
        .update();
  }

  /** 未启用模型打分时，把剩余待打分条目统一标为仅规则分。 */
  public void markScoringUnavailable(UUID batchId, String extraJson) {
    jdbc.sql(
            """
            UPDATE cleaning_item
            SET score_state = 'UNAVAILABLE',
                score_detail = score_detail
                  || CAST(:extra AS jsonb)
            WHERE batch_id = :batch AND score_state = 'PENDING'
            """)
        .param("extra", extraJson)
        .param("batch", batchId)
        .update();
  }

  /** 标记流转成功；只在仍为已通过时生效，返回更新行数。 */
  public int markPromoted(
      List<UUID> ids, UUID spaceId, UUID documentVersionId, UUID reviewer) {
    return jdbc.sql(
            """
            UPDATE cleaning_item
            SET status = 'PROMOTED', promoted_space_id = :space,
                promoted_document_version_id = :document, promoted_by = :reviewer,
                promoted_at = now()
            WHERE id IN (:ids) AND status = 'APPROVED'
            """)
        .param("space", spaceId)
        .param("document", documentVersionId)
        .param("reviewer", reviewer)
        .param("ids", ids)
        .update();
  }

  /** 已流转到指定文档版本的条目数；幂等重放时用它还原结果。 */
  public long promotedTo(UUID documentVersionId) {
    return jdbc.sql(
            "SELECT count(*) FROM cleaning_item WHERE promoted_document_version_id = :document")
        .param("document", documentVersionId)
        .query(Long.class)
        .single();
  }

  /** 重新打分的候选：仅规则分的条目退回待打分。 */
  public int requeueScoring(UUID batchId) {
    return jdbc.sql(
            """
            UPDATE cleaning_item
            SET score_state = 'PENDING', model_score = NULL,
                total_score = rule_score,
                score_detail = score_detail - 'modelReason'
            WHERE batch_id = :batch AND score_state = 'UNAVAILABLE'
            """)
        .param("batch", batchId)
        .update();
  }
}
