package dev.aiqa.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ImportRepository {

  private final JdbcClient jdbc;

  public ImportRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record Batch(UUID id, UUID spaceId, String status, OffsetDateTime createdAt, List<Item> items) {}

  public record Item(
      int ordinal,
      String submittedFilename,
      String normalizedFilename,
      String status,
      UUID documentVersionId,
      String errorCode,
      Long sizeBytes,
      String mediaType,
      Integer versionNumber) {}

  public UUID createBatch(UUID spaceId, UUID createdBy) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO import_batch (id, space_id, created_by, status) VALUES (:id, :space, :by, 'PROCESSING')")
        .param("id", id)
        .param("space", spaceId)
        .param("by", createdBy)
        .update();
    return id;
  }

  public void addItem(UUID batchId, UUID spaceId, Item item) {
    jdbc.sql(
            """
            INSERT INTO import_item
              (batch_id, space_id, ordinal, submitted_filename, normalized_filename, status,
               document_version_id, error_code)
            VALUES (:batch, :space, :ordinal, :submitted, :normalized, :status, :doc, :error)
            """)
        .param("batch", batchId)
        .param("space", spaceId)
        .param("ordinal", item.ordinal())
        .param("submitted", item.submittedFilename())
        .param("normalized", item.normalizedFilename())
        .param("status", item.status())
        .param("doc", item.documentVersionId())
        .param("error", item.errorCode())
        .update();
  }

  public void finishBatch(UUID batchId) {
    jdbc.sql(
            """
            UPDATE import_batch SET status = 'COMPLETED', completed_at = now() WHERE id = :id
            """)
        .param("id", batchId)
        .update();
  }

  public Optional<Batch> findBatch(UUID batchId) {
    return jdbc.sql(
            """
            SELECT b.id, b.space_id, b.created_at,
                   CASE WHEN EXISTS (
                     SELECT 1 FROM import_item i WHERE i.batch_id = b.id
                       AND i.status IN ('QUEUED', 'PROCESSING')
                   ) THEN 'PROCESSING' ELSE 'COMPLETED' END AS status
            FROM import_batch b WHERE b.id = :id
            """)
        .param("id", batchId)
        .query(BatchRow.class)
        .optional()
        .map(row ->
            new Batch(
                row.id(),
                row.spaceId(),
                row.status(),
                row.createdAt(),
                jdbc.sql(
                        """
                        SELECT i.ordinal, i.submitted_filename, i.normalized_filename, i.status,
                               i.document_version_id, i.error_code,
                               v.size_bytes, v.media_type, v.version_number
                        FROM import_item i
                        LEFT JOIN document_version v ON v.id = i.document_version_id
                        WHERE i.batch_id = :id ORDER BY i.ordinal
                        """)
                    .param("id", batchId)
                    .query(Item.class)
                    .list()));
  }

  public Optional<UUID> batchSpace(UUID batchId) {
    return jdbc.sql("SELECT space_id FROM import_batch WHERE id = :id")
        .param("id", batchId)
        .query(UUID.class)
        .optional();
  }

  /** 同一空间同名文件的版本号递增，供界面展示 v1/v2/v3。 */
  public int nextVersionNumber(UUID spaceId, String normalizedFilename) {
    return jdbc.sql(
            """
            SELECT COALESCE(max(version_number), 0) + 1
            FROM document_version WHERE space_id = :space AND normalized_filename = :name
            """)
        .param("space", spaceId)
        .param("name", normalizedFilename)
        .query(Integer.class)
        .single();
  }

  public void updateItemStatus(UUID documentVersionId, String status, String errorCode) {
    jdbc.sql(
            """
            UPDATE import_item SET status = :status, error_code = :error
            WHERE document_version_id = :doc
            """)
        .param("status", status)
        .param("error", errorCode)
        .param("doc", documentVersionId)
        .update();
  }

  public long openJobs() {
    return jdbc.sql("SELECT count(*) FROM job WHERE status IN ('QUEUED', 'RUNNING')").query(Long.class).single();
  }

  record BatchRow(UUID id, UUID spaceId, String status, OffsetDateTime createdAt) {}
}
