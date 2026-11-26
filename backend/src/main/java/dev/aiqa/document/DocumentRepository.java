package dev.aiqa.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DocumentRepository {

  private final JdbcClient jdbc;

  public DocumentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record DocumentRow(
      UUID id,
      UUID spaceId,
      UUID documentId,
      String normalizedFilename,
      String displayFilename,
      String objectKey,
      String sha256,
      String mediaType,
      long sizeBytes,
      String scanState,
      String parseState,
      int tryCount,
      String errorCode,
      int versionNumber,
      OffsetDateTime createdAt) {}

  public record ChunkRow(UUID id, UUID documentVersionId, int ordinal, String body, String sourceLocation) {}

  public record IndexChunk(
      String body, String sourceLocation, String searchVector, float[] embedding) {

    /** 未生成向量时的构造方式；检索会回退词法。 */
    public IndexChunk(String body, String sourceLocation, String searchVector) {
      this(body, sourceLocation, searchVector, null);
    }
  }

  public Optional<DocumentRow> find(UUID spaceId, UUID documentVersionId) {
    return jdbc.sql(
            """
            SELECT id, space_id, document_id, normalized_filename, display_filename, object_key,
                   sha256, media_type, size_bytes, scan_state, parse_state, try_count, error_code,
                   version_number, created_at
            FROM document_version
            WHERE space_id = :space AND id = :id
            """)
        .param("space", spaceId)
        .param("id", documentVersionId)
        .query(DocumentRow.class)
        .optional();
  }

  public boolean referencesObject(String objectKey) {
    return jdbc.sql("SELECT 1 FROM document_version WHERE object_key = :key")
        .param("key", objectKey).query(Integer.class).optional().isPresent();
  }

  /** 同一份逻辑文档的下一个版本号（v1、v2…）。 */
  public int nextVersionNumberForDocument(UUID documentId) {
    return jdbc.sql(
            """
            SELECT COALESCE(max(version_number), 0) + 1
            FROM document_version WHERE document_id = :document
            """)
        .param("document", documentId)
        .query(Integer.class)
        .single();
  }

  public Optional<DocumentRow> findByNormalizedName(UUID spaceId, String normalizedFilename) {
    return jdbc.sql(
            """
            SELECT id, space_id, document_id, normalized_filename, display_filename, object_key,
                   sha256, media_type, size_bytes, scan_state, parse_state, try_count, error_code,
                   version_number, created_at
            FROM document_version
            WHERE space_id = :space AND normalized_filename = :name
            ORDER BY version_number DESC
            LIMIT 1
            """)
        .param("space", spaceId)
        .param("name", normalizedFilename)
        .query(DocumentRow.class)
        .optional();
  }

  public List<DocumentRow> listBySpace(UUID spaceId) {
    return jdbc.sql(
            """
            SELECT id, space_id, document_id, normalized_filename, display_filename, object_key,
                   sha256, media_type, size_bytes, scan_state, parse_state, try_count, error_code,
                   version_number, created_at
            FROM document_version WHERE space_id = :space
            ORDER BY created_at, id
            """)
        .param("space", spaceId)
        .query(DocumentRow.class)
        .list();
  }

  public void insert(DocumentRow row, UUID createdBy) {
    jdbc.sql(
            """
            INSERT INTO document_version
              (id, space_id, document_id, normalized_filename, display_filename, object_key, sha256,
               media_type, size_bytes, scan_state, parse_state, version_number, created_by)
            VALUES (:id, :space, :document, :normalized, :display, :key, :sha, :media, :size,
                    :scan, :parse, :version, :by)
            """)
        .param("id", row.id())
        .param("space", row.spaceId())
        .param("document", row.documentId())
        .param("normalized", row.normalizedFilename())
        .param("display", row.displayFilename())
        .param("key", row.objectKey())
        .param("sha", row.sha256())
        .param("media", row.mediaType())
        .param("size", row.sizeBytes())
        .param("scan", row.scanState())
        .param("parse", row.parseState())
        .param("version", row.versionNumber())
        .param("by", createdBy)
        .update();
  }

  @Transactional
  public void replaceIndex(UUID spaceId, UUID documentVersionId, List<IndexChunk> chunks) {
    replaceChunks(spaceId, documentVersionId, chunks);
  }

  @Transactional
  public void completeParse(UUID spaceId, UUID documentVersionId, List<IndexChunk> chunks) {
    replaceChunks(spaceId, documentVersionId, chunks);
    markParsed(spaceId, documentVersionId, "READY", null);
  }

  private void replaceChunks(UUID spaceId, UUID documentVersionId, List<IndexChunk> chunks) {
    if (chunks.isEmpty() || chunks.stream().anyMatch(chunk -> chunk.body().isBlank())) {
      throw new IllegalArgumentException("没有可索引的文档正文");
    }
    // Concurrent parse and rebuild attempts must serialize even when no chunks exist yet.
    jdbc.sql("SELECT id FROM document_version WHERE space_id = :space AND id = :doc FOR UPDATE")
        .param("space", spaceId)
        .param("doc", documentVersionId)
        .query(UUID.class)
        .optional()
        .orElseThrow(() -> new IllegalStateException("文档已不存在"));
    jdbc.sql("DELETE FROM document_chunk WHERE space_id = :space AND document_version_id = :doc")
        .param("space", spaceId)
        .param("doc", documentVersionId)
        .update();
    int ordinal = 0;
    for (IndexChunk chunk : chunks) {
      jdbc.sql(
              """
              INSERT INTO document_chunk
                (id, space_id, document_version_id, ordinal, body, source_location, search_vector,
                 embedding)
              VALUES (:id, :space, :doc, :ordinal, :body, :location, to_tsvector('simple', :vector),
                      CAST(:embedding AS real[]))
              """)
          .param("id", UUID.randomUUID())
          .param("space", spaceId)
          .param("doc", documentVersionId)
          .param("ordinal", ordinal++)
          .param("body", chunk.body())
          .param("location", chunk.sourceLocation())
          .param("vector", chunk.searchVector() == null ? "" : chunk.searchVector())
          .param("embedding", dev.aiqa.search.EmbeddingClient.literal(chunk.embedding()))
          .update();
    }
  }

  public List<ChunkRow> chunks(UUID spaceId, UUID documentVersionId) {
    return jdbc.sql(
            """
            SELECT id, document_version_id, ordinal, body, source_location FROM document_chunk
            WHERE space_id = :space AND document_version_id = :doc
            ORDER BY ordinal
            """)
        .param("space", spaceId)
        .param("doc", documentVersionId)
        .query(ChunkRow.class)
        .list();
  }

  public void markParsed(UUID spaceId, UUID documentVersionId, String state, String errorCode) {
    jdbc.sql(
            """
            UPDATE document_version
            SET parse_state = :state, error_code = :error, try_count = try_count + 1
            WHERE space_id = :space AND id = :id
            """)
        .param("state", state)
        .param("error", errorCode)
        .param("space", spaceId)
        .param("id", documentVersionId)
        .update();
  }

  public void resetForRetry(UUID spaceId, UUID documentVersionId) {
    jdbc.sql(
            """
            UPDATE document_version SET parse_state = 'QUEUED', error_code = NULL
            WHERE space_id = :space AND id = :id AND parse_state = 'FAILED'
            """)
        .param("space", spaceId)
        .param("id", documentVersionId)
        .update();
  }

  /** Visible only when the document is a member of the space's current publication snapshot. */
  public boolean visibleInCurrentPublication(UUID spaceId, UUID documentVersionId) {
    return jdbc.sql(
            """
            SELECT 1 FROM space s
            JOIN space_publication p ON p.id = s.current_publication_id AND p.space_id = s.id
            JOIN space_snapshot_document d ON d.snapshot_id = p.snapshot_id AND d.space_id = s.id
            WHERE s.id = :space AND d.document_version_id = :doc
            """)
        .param("space", spaceId)
        .param("doc", documentVersionId)
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  /** Documents that belong to one frozen snapshot, for the reviewer's preview. */
  public boolean inSnapshot(UUID spaceId, UUID snapshotId, UUID documentVersionId) {
    return jdbc.sql(
            """
            SELECT 1 FROM space_snapshot_document
            WHERE space_id = :space AND snapshot_id = :snapshot AND document_version_id = :doc
            """)
        .param("space", spaceId)
        .param("snapshot", snapshotId)
        .param("doc", documentVersionId)
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  /**
   * 管理员使用侧的可见范围：最近一次发布快照（含已撤回）或当前草稿。
   * 检索只会命中这两类内容，预览与下载据此保持一致。
   */
  public boolean inLatestPublicationOrDraft(UUID spaceId, UUID documentVersionId) {
    Boolean visible =
        jdbc.sql(
                """
                SELECT EXISTS (
                         SELECT 1 FROM space_snapshot_document d
                         JOIN space_publication p
                           ON p.snapshot_id = d.snapshot_id AND p.space_id = d.space_id
                         WHERE d.space_id = :space AND d.document_version_id = :doc
                           AND p.epoch = (SELECT max(epoch) FROM space_publication WHERE space_id = :space)
                       )
                       OR EXISTS (
                         SELECT 1 FROM space_draft_document d
                         WHERE d.space_id = :space AND d.document_version_id = :doc
                       )
                """)
            .param("space", spaceId)
            .param("doc", documentVersionId)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(visible);
  }
}
