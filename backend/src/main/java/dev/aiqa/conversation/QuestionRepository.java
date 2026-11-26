package dev.aiqa.conversation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QuestionRepository {

  private final JdbcClient jdbc;

  public QuestionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record QuestionRow(
      UUID id, UUID conversationId, UUID userId, String text, String requestedSpaceIds,
      String status, String failureCode, OffsetDateTime acceptedAt, UUID requestedModelId) {}

  public record ParagraphRow(UUID id, int ordinal, String body, String kind) {}

  public record EvidenceRow(
      UUID paragraphId, UUID spaceId, UUID publicationId, long epoch, UUID documentVersionId,
      String displayName) {}

  public record CitationRow(UUID id, UUID paragraphId, String label, int marker) {}

  public UUID insert(
      UUID conversationId, UUID userId, String requestKey, String text, List<UUID> requestedSpaceIds,
      UUID requestedModelId) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO question (id, conversation_id, user_id, request_key, text,
                                  requested_space_ids, status, requested_model_id)
            VALUES (:id, :conversation, :user, :key, :text, :spaces, 'QUEUED', :model)
            """)
        .param("id", id).param("conversation", conversationId).param("user", userId)
        .param("key", requestKey).param("text", text)
        .param("spaces", requestedSpaceIds.toArray(UUID[]::new))
        .param("model", requestedModelId, java.sql.Types.OTHER).update();
    return id;
  }

  public Optional<UUID> findByRequestKey(UUID userId, String requestKey) {
    return jdbc.sql("SELECT id FROM question WHERE user_id = :user AND request_key = :key")
        .param("user", userId).param("key", requestKey).query(UUID.class).optional();
  }

  public Optional<QuestionRow> find(UUID id, UUID userId) {
    return jdbc.sql(
            """
            SELECT q.id, q.conversation_id, q.user_id, q.text,
                   COALESCE(array_to_string(q.requested_space_ids, ','), '') AS requested_space_ids,
                   q.status, q.failure_code, q.accepted_at, q.requested_model_id
            FROM question q JOIN conversation c ON c.id = q.conversation_id
            WHERE q.id = :id AND c.owner_id = :user AND c.deleted_at IS NULL
            """)
        .param("id", id).param("user", userId).query(QuestionRow.class).optional();
  }

  public Optional<QuestionRow> findAny(UUID id) {
    return jdbc.sql(
            """
            SELECT id, conversation_id, user_id, text, COALESCE(array_to_string(requested_space_ids, ','), '') AS requested_space_ids, status, failure_code, accepted_at, requested_model_id
            FROM question WHERE id = :id
            """)
        .param("id", id).query(QuestionRow.class).optional();
  }

  public List<QuestionRow> listByConversation(UUID conversationId, UUID userId) {
    return jdbc.sql(
            """
            SELECT q.id, q.conversation_id, q.user_id, q.text,
                   COALESCE(array_to_string(q.requested_space_ids, ','), '') AS requested_space_ids,
                   q.status, q.failure_code, q.accepted_at, q.requested_model_id
            FROM question q JOIN conversation c ON c.id = q.conversation_id
            WHERE c.id = :conversation AND c.owner_id = :user AND c.deleted_at IS NULL
            ORDER BY q.accepted_at, q.id
            """)
        .param("conversation", conversationId).param("user", userId)
        .query(QuestionRow.class).list();
  }

  public boolean markRunning(UUID id) {
    return jdbc.sql("UPDATE question SET status = 'RUNNING', run_revision = run_revision + 1 WHERE id = :id AND status = 'QUEUED'")
        .param("id", id).update() == 1;
  }

  public void succeed(UUID id) {
    jdbc.sql("UPDATE question SET status = 'SUCCEEDED', finished_at = now() WHERE id = :id AND status = 'RUNNING'")
        .param("id", id).update();
  }

  public void fail(UUID id, String failureCode) {
    jdbc.sql(
            """
            UPDATE question SET status = 'FAILED', failure_code = :code, finished_at = now()
            WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
            """)
        .param("code", failureCode).param("id", id).update();
  }

  public UUID insertParagraph(UUID questionId, int ordinal, String body, String kind) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO answer_paragraph (id, question_id, ordinal, body, answer_kind)
            VALUES (:id, :question, :ordinal, :body, :kind)
            """)
        .param("id", id).param("question", questionId).param("ordinal", ordinal)
        .param("body", body).param("kind", kind).update();
    return id;
  }

  public List<ParagraphRow> paragraphs(UUID questionId) {
    return jdbc.sql(
            """
            SELECT id, ordinal, body, answer_kind AS kind FROM answer_paragraph
            WHERE question_id = :question ORDER BY ordinal
            """)
        .param("question", questionId).query(ParagraphRow.class).list();
  }

  public void recordUsage(String callId, UUID questionId, UUID userId, Long input, Long output) {
    jdbc.sql(
            """
            INSERT INTO model_call_usage (call_id, question_id, user_id, input_tokens, output_tokens)
            VALUES (:call, :question, :user, :input, :output)
            ON CONFLICT (call_id) DO NOTHING
            """)
        .param("call", callId).param("question", questionId).param("user", userId)
        .param("input", input).param("output", output).update();
  }

  public void insertEvidence(
      UUID paragraphId, String callId, int ordinal, UUID spaceId, UUID publicationId, long epoch,
      UUID documentVersionId, String excerpt, String sourceLocation) {
    jdbc.sql(
            """
            INSERT INTO model_call_evidence
              (call_id, ordinal, space_id, publication_id, epoch, document_version_id,
               evidence_excerpt, source_location)
            VALUES (:call, :ordinal, :space, :publication, :epoch, :doc, :excerpt, :location)
            """)
        .param("call", callId).param("ordinal", ordinal)
        .param("space", spaceId).param("publication", publicationId)
        .param("epoch", epoch).param("doc", documentVersionId)
        .param("excerpt", excerpt).param("location", sourceLocation).update();
    jdbc.sql(
            """
            INSERT INTO paragraph_evidence (paragraph_id, call_id, ordinal)
            VALUES (:paragraph, :call, :ordinal)
            """)
        .param("paragraph", paragraphId).param("call", callId).param("ordinal", ordinal).update();
  }

  public List<EvidenceRow> evidence(UUID questionId) {
    return jdbc.sql(
            """
            SELECT e.paragraph_id, v.space_id, v.publication_id, v.epoch, v.document_version_id,
                   d.display_filename AS "displayName"
            FROM answer_paragraph p
            JOIN paragraph_evidence e ON e.paragraph_id = p.id
            JOIN model_call_evidence v ON v.call_id = e.call_id AND v.ordinal = e.ordinal
            JOIN document_version d ON d.id = v.document_version_id AND d.space_id = v.space_id
            WHERE p.question_id = :question
            ORDER BY p.ordinal, v.ordinal
            """)
        .param("question", questionId).query(EvidenceRow.class).list();
  }

  public UUID insertCitation(UUID paragraphId, String callId, int evidenceOrdinal, String label) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO citation (id, paragraph_id, call_id, evidence_ordinal, display_label)
            VALUES (:id, :paragraph, :call, :ordinal, :label)
            """)
        .param("id", id).param("paragraph", paragraphId).param("call", callId)
        .param("ordinal", evidenceOrdinal).param("label", label).update();
    return id;
  }

  public List<CitationRow> citations(UUID questionId) {
    return jdbc.sql(
            """
            SELECT c.id, c.paragraph_id, c.display_label AS label,
                   c.evidence_ordinal + 1 AS marker
            FROM citation c JOIN answer_paragraph p ON p.id = c.paragraph_id
            WHERE p.question_id = :question ORDER BY p.ordinal, c.evidence_ordinal
            """)
        .param("question", questionId).query(CitationRow.class).list();
  }

  public Optional<CitationRow> citation(UUID citationId) {
    return jdbc.sql(
            """
            SELECT id, paragraph_id, display_label AS label, evidence_ordinal + 1 AS marker
            FROM citation WHERE id = :id
            """)
        .param("id", citationId).query(CitationRow.class).optional();
  }

  public record CitationTarget(
      UUID ownerId, UUID paragraphId, UUID spaceId, UUID documentVersionId, String label,
      String excerpt, String sourceLocation, String policyState,
      java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveUntil) {}

  public Optional<CitationTarget> citationTarget(UUID citationId) {
    return jdbc.sql(
            """
            SELECT conv.owner_id, c.paragraph_id, v.space_id, v.document_version_id,
                   c.display_label AS label, v.evidence_excerpt AS excerpt, v.source_location,
                   sd.policy_state, sd.effective_from, sd.effective_until
            FROM citation c
            JOIN answer_paragraph p ON p.id = c.paragraph_id
            JOIN question q ON q.id = p.question_id
            JOIN conversation conv ON conv.id = q.conversation_id
            JOIN paragraph_evidence e ON e.paragraph_id = p.id AND e.call_id = c.call_id
                                     AND e.ordinal = c.evidence_ordinal
            JOIN model_call_evidence v ON v.call_id = e.call_id AND v.ordinal = e.ordinal
            JOIN space_publication pub ON pub.id = v.publication_id AND pub.space_id = v.space_id
            JOIN space_snapshot_document sd ON sd.snapshot_id = pub.snapshot_id AND sd.space_id = v.space_id
                                           AND sd.document_version_id = v.document_version_id
            WHERE c.id = :id AND conv.deleted_at IS NULL
            """)
        .param("id", citationId).query(CitationTarget.class).optional();
  }

  public boolean paragraphStillAuthorized(UUID paragraphId, UUID userId) {
    return jdbc.sql(
            """
            SELECT EXISTS (
              SELECT 1 FROM answer_paragraph p JOIN question q ON q.id = p.question_id
              WHERE p.id = :paragraph AND q.user_id = :user
                AND (p.answer_kind = 'NO_EVIDENCE' OR EXISTS (
                  SELECT 1 FROM paragraph_evidence e WHERE e.paragraph_id = p.id
                ))
                AND NOT EXISTS (
                  SELECT 1 FROM paragraph_evidence e
                  JOIN model_call_evidence v ON v.call_id = e.call_id AND v.ordinal = e.ordinal
                  JOIN space s ON s.id = v.space_id
                  LEFT JOIN space_member m ON m.space_id = s.id AND m.user_id = :user AND m.can_read
                  LEFT JOIN space_publication source_pub ON source_pub.space_id = s.id
                     AND source_pub.id = v.publication_id AND source_pub.epoch = v.epoch
                     AND source_pub.epoch > s.last_withdrawal_epoch
                  LEFT JOIN space_publication current_pub ON current_pub.space_id = s.id
                     AND current_pub.id = s.current_publication_id
                  LEFT JOIN space_snapshot_document sd ON sd.snapshot_id = current_pub.snapshot_id
                     AND sd.space_id = s.id AND sd.document_version_id = v.document_version_id
                  WHERE e.paragraph_id = p.id
                    AND ((m.user_id IS NULL AND NOT EXISTS (
                      SELECT 1 FROM user_role r WHERE r.user_id = :user AND r.role = 'SYSTEM_ADMIN'
                    )) OR source_pub.id IS NULL OR sd.document_version_id IS NULL)
                )
            )
            """)
        .param("paragraph", paragraphId).param("user", userId).query(Boolean.class).single();
  }
}
