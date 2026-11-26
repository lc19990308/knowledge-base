package dev.aiqa.publish;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PublishRepository {

  private final JdbcClient jdbc;

  public PublishRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record SnapshotEntry(
      UUID documentVersionId,
      java.time.LocalDate effectiveFrom,
      java.time.LocalDate effectiveUntil,
      String policyState,
      UUID replacesDocumentVersionId,
      String displayFilename,
      String parseState) {}

  public record Request(
      UUID id,
      UUID spaceId,
      UUID snapshotId,
      UUID basePublicationId,
      long baseEpoch,
      String status,
      UUID submittedBy,
      String submissionReason,
      OffsetDateTime submittedAt,
      UUID reviewedBy,
      String decisionReason,
      OffsetDateTime decidedAt) {}

  public record DecisionSnapshot(long revision, String status) {}

  public UUID createSnapshot(UUID spaceId, long draftRevision, String digest, UUID createdBy) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO space_snapshot (id, space_id, source_draft_revision, content_digest, created_by)
            VALUES (:id, :space, :revision, :digest, :by)
            """)
        .param("id", id).param("space", spaceId).param("revision", draftRevision)
        .param("digest", digest).param("by", createdBy).update();
    jdbc.sql(
            """
            INSERT INTO space_snapshot_document
              (space_id, snapshot_id, document_version_id, effective_from, effective_until,
               policy_state, replaces_document_version_id)
            SELECT space_id, :snapshot, document_version_id, effective_from, effective_until,
                   policy_state, replaces_document_version_id
            FROM space_draft_document WHERE space_id = :space
            """)
        .param("snapshot", id).param("space", spaceId).update();
    return id;
  }

  public List<SnapshotEntry> snapshotEntries(UUID spaceId, UUID snapshotId) {
    return jdbc.sql(
            """
            SELECT d.document_version_id, d.effective_from, d.effective_until, d.policy_state,
                   d.replaces_document_version_id, v.display_filename, v.parse_state
            FROM space_snapshot_document d
            JOIN document_version v ON v.id = d.document_version_id AND v.space_id = d.space_id
            WHERE d.space_id = :space AND d.snapshot_id = :snapshot
            ORDER BY d.document_version_id
            """)
        .param("space", spaceId).param("snapshot", snapshotId)
        .query(SnapshotEntry.class).list();
  }

  public UUID createRequest(
      UUID spaceId, UUID snapshotId, UUID basePublicationId, long baseEpoch, UUID submittedBy, String reason) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO publish_request
              (id, space_id, snapshot_id, base_publication_id, base_epoch, status, submitted_by, submission_reason)
            VALUES (:id, :space, :snapshot, :base, :epoch, 'PENDING_REVIEW', :by, :reason)
            """)
        .param("id", id).param("space", spaceId).param("snapshot", snapshotId)
        .param("base", basePublicationId).param("epoch", baseEpoch)
        .param("by", submittedBy).param("reason", reason).update();
    return id;
  }

  public long draftRevisionOfSnapshot(UUID spaceId, UUID snapshotId) {
    return jdbc.sql(
            "SELECT source_draft_revision FROM space_snapshot WHERE space_id = :space AND id = :id")
        .param("space", spaceId)
        .param("id", snapshotId)
        .query(Long.class)
        .optional()
        .orElse(0L);
  }

  public Optional<UUID> publicationOfRequest(UUID requestId) {
    return jdbc.sql("SELECT id FROM space_publication WHERE request_id = :request")
        .param("request", requestId)
        .query(UUID.class)
        .optional();
  }

  public Optional<Request> findRequest(UUID requestId) {
    return jdbc.sql(
            """
            SELECT id, space_id, snapshot_id, base_publication_id, base_epoch, status, submitted_by,
                   submission_reason, submitted_at, reviewed_by, decision_reason, decided_at
            FROM publish_request WHERE id = :id
            """)
        .param("id", requestId).query(Request.class).optional();
  }

  public List<Request> listRequests(UUID spaceId) {
    return jdbc.sql(
            """
            SELECT id, space_id, snapshot_id, base_publication_id, base_epoch, status, submitted_by,
                   submission_reason, submitted_at, reviewed_by, decision_reason, decided_at
            FROM publish_request WHERE space_id = :space ORDER BY submitted_at DESC, id
            """)
        .param("space", spaceId).query(Request.class).list();
  }

  /** Admin sees every request; anyone else sees only the requests they submitted. */
  public List<Request> listVisible(UUID userId, boolean isAdmin) {
    if (isAdmin) {
      return jdbc.sql(
              """
              SELECT id, space_id, snapshot_id, base_publication_id, base_epoch, status, submitted_by,
                     submission_reason, submitted_at, reviewed_by, decision_reason, decided_at
              FROM publish_request ORDER BY submitted_at DESC, id
              """)
          .query(Request.class)
          .list();
    }
    return jdbc.sql(
            """
            SELECT id, space_id, snapshot_id, base_publication_id, base_epoch, status, submitted_by,
                   submission_reason, submitted_at, reviewed_by, decision_reason, decided_at
            FROM publish_request WHERE submitted_by = :user ORDER BY submitted_at DESC, id
            """)
        .param("user", userId)
        .query(Request.class)
        .list();
  }

  public Optional<Request> findOpen(UUID spaceId) {
    return jdbc.sql(
            """
            SELECT id, space_id, snapshot_id, base_publication_id, base_epoch, status, submitted_by,
                   submission_reason, submitted_at, reviewed_by, decision_reason, decided_at
            FROM publish_request
            WHERE space_id = :space AND status IN ('PENDING_REVIEW', 'APPROVED_WAITING_INDEX')
            """)
        .param("space", spaceId).query(Request.class).optional();
  }

  public void lockRequest(UUID requestId) {
    jdbc.sql("SELECT id FROM publish_request WHERE id = :id FOR UPDATE")
        .param("id", requestId).query(UUID.class).optional();
  }

  public DecisionSnapshot decisionSnapshot(UUID requestId) {
    return jdbc.sql(
            """
            SELECT (SELECT draft_revision FROM space_draft d WHERE d.space_id = r.space_id) AS revision,
                   r.status
            FROM publish_request r WHERE r.id = :id
            """)
        .param("id", requestId).query(DecisionSnapshot.class).single();
  }

  public void reject(UUID requestId, UUID reviewer, String reason) {
    jdbc.sql(
            """
            UPDATE publish_request
            SET status = 'REJECTED', reviewed_by = :by, decision_reason = :reason, decided_at = now()
            WHERE id = :id AND status IN ('PENDING_REVIEW', 'APPROVED_WAITING_INDEX')
            """)
        .param("by", reviewer).param("reason", reason).param("id", requestId).update();
  }

  public void markWaitingIndex(UUID requestId, UUID reviewer, String reason) {
    jdbc.sql(
            """
            UPDATE publish_request
            SET status = 'APPROVED_WAITING_INDEX', reviewed_by = :by, decision_reason = :reason, decided_at = now()
            WHERE id = :id AND status = 'PENDING_REVIEW'
            """)
        .param("by", reviewer).param("reason", reason).param("id", requestId).update();
  }

  public void cancelOpen(UUID spaceId) {
    jdbc.sql(
            """
            UPDATE publish_request
            SET status = 'CANCELLED', decided_at = now(), decision_reason = '发布已撤回，旧申请作废'
            WHERE space_id = :space AND status IN ('PENDING_REVIEW', 'APPROVED_WAITING_INDEX')
            """)
        .param("space", spaceId).update();
  }

  public void cancelRequest(UUID requestId) {
    jdbc.sql(
            """
            UPDATE publish_request
            SET status = 'CANCELLED', decided_at = now(), decision_reason = '发布指针已变化，申请作废'
            WHERE id = :id AND status IN ('PENDING_REVIEW', 'APPROVED_WAITING_INDEX')
            """)
        .param("id", requestId).update();
  }

  public Optional<UUID> currentPublication(UUID spaceId) {
    return jdbc.sql("SELECT current_publication_id FROM space WHERE id = :id")
        .param("id", spaceId).query(UUID.class).optional();
  }

  public long epoch(UUID spaceId) {
    return jdbc.sql("SELECT publication_epoch FROM space WHERE id = :id")
        .param("id", spaceId).query(Long.class).single();
  }

  public static final class Waiting {
    public boolean allSearchableReady;

    public Waiting(boolean ready) {
      this.allSearchableReady = ready;
    }
  }

  /** True when every snapshot document that can be searched is READY (or NO_TEXT is excluded). */
  public boolean searchableReady(UUID spaceId, UUID snapshotId) {
    Long notReady =
        jdbc.sql(
                """
                SELECT count(*) FROM space_snapshot_document d
                JOIN document_version v ON v.id = d.document_version_id AND v.space_id = d.space_id
                WHERE d.space_id = :space AND d.snapshot_id = :snapshot
                  AND v.parse_state IN ('QUEUED', 'PROCESSING', 'FAILED')
                """)
            .param("space", spaceId).param("snapshot", snapshotId)
            .query(Long.class).single();
    return notReady == 0;
  }

  public UUID publish(UUID spaceId, UUID requestId, UUID reviewer, String reason, long baseEpoch) {
    Request request = findRequest(requestId).orElseThrow(dev.aiqa.common.ApiException::notFound);
    long currentEpoch = epoch(spaceId);
    if (currentEpoch != baseEpoch) {
      throw dev.aiqa.common.ApiException.conflict(dev.aiqa.common.ErrorCode.PUBLICATION_CHANGED);
    }
    long nextEpoch = currentEpoch + 1;
    UUID publicationId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO space_publication (id, space_id, snapshot_id, request_id, epoch)
            VALUES (:id, :space, :snapshot, :request, :epoch)
            """)
        .param("id", publicationId).param("space", spaceId)
        .param("snapshot", request.snapshotId()).param("request", requestId)
        .param("epoch", nextEpoch).update();
    jdbc.sql(
            """
            UPDATE space SET current_publication_id = :pub, publication_epoch = :epoch,
                             state_version = state_version + 1, updated_at = now()
            WHERE id = :space
            """)
        .param("pub", publicationId).param("epoch", nextEpoch).param("space", spaceId).update();
    jdbc.sql(
            """
            UPDATE publish_request
            SET status = 'PUBLISHED', reviewed_by = :by, decision_reason = :reason, decided_at = now()
            WHERE id = :id
            """)
        .param("by", reviewer).param("reason", reason).param("id", requestId).update();
    return publicationId;
  }

  public void withdraw(UUID spaceId, UUID expectedPublicationId, UUID actor, String reason) {
    UUID current = currentPublication(spaceId).orElse(null);
    if (current == null) {
      throw dev.aiqa.common.ApiException.notFound();
    }
    if (!current.equals(expectedPublicationId)) {
      throw dev.aiqa.common.ApiException.conflict(dev.aiqa.common.ErrorCode.PUBLICATION_CHANGED);
    }
    jdbc.sql(
            """
            UPDATE space SET current_publication_id = NULL, publication_epoch = publication_epoch + 1,
                             state_version = state_version + 1, updated_at = now()
            WHERE id = :space
            """)
        .param("space", spaceId).update();
  }

  public record PublicationRow(UUID id, UUID spaceId, UUID snapshotId, long epoch, OffsetDateTime publishedAt) {}

  public List<PublicationRow> publications(UUID spaceId) {
    return jdbc.sql(
            """
            SELECT id, space_id, snapshot_id, epoch, published_at
            FROM space_publication WHERE space_id = :space ORDER BY epoch DESC
            """)
        .param("space", spaceId).query(PublicationRow.class).list();
  }
}
