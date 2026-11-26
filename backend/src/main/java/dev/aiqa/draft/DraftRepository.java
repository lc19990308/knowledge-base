package dev.aiqa.draft;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DraftRepository {

  private final JdbcClient jdbc;

  public DraftRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 条目内联文档元信息，避免客户端逐个再查详情（N+1）。 */
  public record Entry(
      UUID documentVersionId,
      LocalDate effectiveFrom,
      LocalDate effectiveUntil,
      String policyState,
      UUID replacesDocumentVersionId,
      String name,
      String parseState,
      Long sizeBytes,
      String mediaType,
      Integer versionNumber,
      java.time.OffsetDateTime createdAt) {}

  public record Draft(UUID spaceId, long revision, List<Entry> entries) {}

  public Optional<Long> revision(UUID spaceId) {
    return jdbc.sql("SELECT draft_revision FROM space_draft WHERE space_id = :id")
        .param("id", spaceId).query(Long.class).optional();
  }

  public void ensure(UUID spaceId, UUID userId) {
    jdbc.sql(
            """
            INSERT INTO space_draft (space_id, updated_by) VALUES (:id, :user)
            ON CONFLICT (space_id) DO NOTHING
            """)
        .param("id", spaceId).param("user", userId).update();
  }

  public List<Entry> entries(UUID spaceId) {
    return jdbc.sql(
            """
            SELECT d.document_version_id, d.effective_from, d.effective_until, d.policy_state,
                   d.replaces_document_version_id,
                   v.display_filename AS name, v.parse_state, v.size_bytes, v.media_type,
                   v.version_number, v.created_at
            FROM space_draft_document d
            LEFT JOIN document_version v
              ON v.id = d.document_version_id AND v.space_id = d.space_id
            WHERE d.space_id = :id
            ORDER BY document_version_id
            """)
        .param("id", spaceId).query(Entry.class).list();
  }

  public Draft load(UUID spaceId) {
    long revision = revision(spaceId).orElse(0L);
    return new Draft(spaceId, revision, entries(spaceId));
  }

  /** Replaces the whole entry list under the draft row lock. */
  public Draft replace(UUID spaceId, UUID userId, long expectedRevision, List<Entry> next) {
    jdbc.sql("SELECT space_id FROM space_draft WHERE space_id = :id FOR UPDATE")
        .param("id", spaceId).query(UUID.class).optional();
    long current = revision(spaceId).orElseThrow();
    if (current != expectedRevision) {
      throw dev.aiqa.common.ApiException.preconditionFailed(dev.aiqa.common.ErrorCode.ETAG_MISMATCH);
    }
    jdbc.sql("DELETE FROM space_draft_document WHERE space_id = :id").param("id", spaceId).update();
    for (Entry entry : next) {
      jdbc.sql(
              """
              INSERT INTO space_draft_document
                (space_id, document_version_id, effective_from, effective_until, policy_state,
                 replaces_document_version_id)
              VALUES (:space, :doc, :from, :until, :state, :replaces)
              """)
          .param("space", spaceId)
          .param("doc", entry.documentVersionId())
          .param("from", entry.effectiveFrom())
          .param("until", entry.effectiveUntil())
          .param("state", entry.policyState())
          .param("replaces", entry.replacesDocumentVersionId())
          .update();
    }
    jdbc.sql(
            """
            UPDATE space_draft SET draft_revision = draft_revision + 1, updated_by = :user, updated_at = now()
            WHERE space_id = :id
            """)
        .param("user", userId).param("id", spaceId).update();
    return load(spaceId);
  }

  public long snapshotRevision(UUID spaceId) {
    return revision(spaceId).orElse(0L);
  }

  public void touch(UUID spaceId, UUID userId) {
    jdbc.sql(
            """
            UPDATE space_draft SET draft_revision = draft_revision + 1, updated_by = :user, updated_at = now()
            WHERE space_id = :id
            """)
        .param("user", userId).param("id", spaceId).update();
  }

  public boolean isInDraft(UUID spaceId, UUID documentVersionId) {
    return jdbc.sql(
            """
            SELECT 1 FROM space_draft_document WHERE space_id = :space AND document_version_id = :doc
            """)
        .param("space", spaceId).param("doc", documentVersionId).query(Integer.class).optional().isPresent();
  }
}
