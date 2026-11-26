package dev.aiqa.persistence;

import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable worker queue. Leases are granted in a short transaction and never span execution. */
@Repository
public class JobRepository {

  public enum Kind {
    PARSE("PARSE_DOCUMENT", 1), INDEX("INDEX_DOCUMENT", 2),
    PUBLISH("PUBLISH_SNAPSHOT", 3), ANSWER("ANSWER_QUESTION", 4),
    GENERATE_TEMPLATE("GENERATE_TEMPLATE", 5),
    CLEAN("CLEAN_DATASET", 6), CLEAN_SCORE("SCORE_CLEANING", 7);

    private final String type;
    private final int lockKey;

    Kind(String type, int lockKey) {
      this.type = type;
      this.lockKey = lockKey;
    }
  }

  private static final int ADVISORY_LOCK_NAMESPACE = 1262637393;
  private final JdbcClient jdbc;
  private final Map<Kind, Integer> capacity;
  private final Map<Kind, Integer> concurrency;
  private final int retryAfterSeconds;

  public JobRepository(JdbcClient jdbc,
      @Value("${aiqa.worker.capacity.parse:500}") int parseCapacity,
      @Value("${aiqa.worker.capacity.index:2500}") int indexCapacity,
      @Value("${aiqa.worker.capacity.publish:200}") int publishCapacity,
      @Value("${aiqa.worker.capacity.answer:200}") int answerCapacity,
      @Value("${aiqa.worker.capacity.generation:50}") int generationCapacity,
      @Value("${aiqa.worker.capacity.clean:200}") int cleanCapacity,
      @Value("${aiqa.worker.capacity.clean-score:200}") int cleanScoreCapacity,
      @Value("${aiqa.worker.capacity.retry-after-seconds:5}") int retryAfterSeconds,
      @Value("${aiqa.worker.concurrency.parse:2}") int parseConcurrency,
      @Value("${aiqa.worker.concurrency.index:1}") int indexConcurrency,
      @Value("${aiqa.worker.concurrency.publish:1}") int publishConcurrency,
      @Value("${aiqa.worker.concurrency.answer:2}") int answerConcurrency,
      @Value("${aiqa.worker.concurrency.generation:1}") int generationConcurrency,
      @Value("${aiqa.worker.concurrency.clean:1}") int cleanConcurrency,
      @Value("${aiqa.worker.concurrency.clean-score:1}") int cleanScoreConcurrency) {
    this.jdbc = jdbc;
    this.capacity = Map.of(Kind.PARSE, parseCapacity, Kind.INDEX, indexCapacity,
        Kind.PUBLISH, publishCapacity, Kind.ANSWER, answerCapacity,
        Kind.GENERATE_TEMPLATE, generationCapacity,
        Kind.CLEAN, cleanCapacity, Kind.CLEAN_SCORE, cleanScoreCapacity);
    this.concurrency = Map.of(Kind.PARSE, parseConcurrency, Kind.INDEX, indexConcurrency,
        Kind.PUBLISH, publishConcurrency, Kind.ANSWER, answerConcurrency,
        Kind.GENERATE_TEMPLATE, generationConcurrency,
        Kind.CLEAN, cleanConcurrency, Kind.CLEAN_SCORE, cleanScoreConcurrency);
    this.retryAfterSeconds = retryAfterSeconds;
    if (retryAfterSeconds < 1) {
      throw new IllegalArgumentException("任务容量的重试间隔必须大于零");
    }
    for (Kind kind : Kind.values()) {
      if (concurrency.get(kind) < 1 || capacity.get(kind) < concurrency.get(kind)) {
        throw new IllegalArgumentException("任务容量必须不小于正数并发限制: " + kind);
      }
    }
  }

  private void lock(Kind kind) {
    // Admission and claim counts must be serialized across instances of this schema.
    jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtext(current_schema()) # :namespace, :kind)")
        .param("namespace", ADVISORY_LOCK_NAMESPACE)
        .param("kind", kind.lockKey)
        .query(Integer.class).single();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireCapacity(Kind kind, int additional) {
    lock(kind);
    long active = jdbc.sql("SELECT count(*) FROM job WHERE job_type = :type AND status IN ('QUEUED', 'RUNNING')")
        .param("type", kind.type).query(Long.class).single();
    if (active + additional > capacity.get(kind)) {
      throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
          "任务队列已满，请稍后重试", (long) retryAfterSeconds);
    }
  }

  public record Job(
      UUID id, String jobType, UUID spaceId, UUID documentVersionId, UUID questionId,
      UUID publishRequestId, UUID templateGenerationId, UUID cleaningBatchId, int attemptCount,
      UUID leaseToken) {}

  public void enqueueParse(UUID documentId, UUID spaceId) {
    jdbc.sql(
            """
            INSERT INTO job (id, job_type, space_id, document_version_id, status)
            VALUES (:id, 'PARSE_DOCUMENT', :space, :doc, 'QUEUED')
            ON CONFLICT DO NOTHING
            """)
        .param("id", UUID.randomUUID())
        .param("space", spaceId)
        .param("doc", documentId)
        .update();
  }

  public boolean enqueueIndex(UUID documentId, UUID spaceId) {
    int inserted = jdbc.sql(
            """
            INSERT INTO job (id, job_type, space_id, document_version_id, status)
            VALUES (:id, 'INDEX_DOCUMENT', :space, :doc, 'QUEUED')
            ON CONFLICT DO NOTHING
            """)
        .param("id", UUID.randomUUID())
        .param("space", spaceId)
        .param("doc", documentId)
        .update();
    requireCapacity(Kind.INDEX, 0);
    return inserted == 1;
  }

  public void enqueuePublish(UUID requestId, UUID spaceId) {
    jdbc.sql(
            """
            INSERT INTO job (id, job_type, space_id, publish_request_id, status)
            VALUES (:id, 'PUBLISH_SNAPSHOT', :space, :request, 'QUEUED')
            ON CONFLICT DO NOTHING
            """)
        .param("id", UUID.randomUUID())
        .param("space", spaceId)
        .param("request", requestId)
        .update();
  }

  public void enqueueGeneration(UUID generationId) {
    jdbc.sql(
            """
            INSERT INTO job (id, job_type, template_generation_id, status)
            VALUES (:id, 'GENERATE_TEMPLATE', :generation, 'QUEUED')
            ON CONFLICT DO NOTHING
            """)
        .param("id", UUID.randomUUID())
        .param("generation", generationId)
        .update();
    requireCapacity(Kind.GENERATE_TEMPLATE, 0);
  }

  /** 清洗任务：同一批次同一类型只保留一个未结束任务，重复入队由唯一索引挡住。 */
  public void enqueueCleaning(UUID batchId, UUID spaceId) {
    jdbc.sql(
            """
            INSERT INTO job (id, job_type, space_id, cleaning_batch_id, status)
            VALUES (:id, 'CLEAN_DATASET', CAST(:space AS uuid), :batch, 'QUEUED')
            ON CONFLICT DO NOTHING
            """)
        .param("id", UUID.randomUUID())
        .param("space", spaceId)
        .param("batch", batchId)
        .update();
  }

  /** 模型打分任务：与清洗任务同一模式，一个批次同时只跑一个。 */
  public void enqueueCleaningScore(UUID batchId, UUID spaceId) {
    jdbc.sql(
            """
            INSERT INTO job (id, job_type, space_id, cleaning_batch_id, status)
            VALUES (:id, 'SCORE_CLEANING', CAST(:space AS uuid), :batch, 'QUEUED')
            ON CONFLICT DO NOTHING
            """)
        .param("id", UUID.randomUUID())
        .param("space", spaceId)
        .param("batch", batchId)
        .update();
  }

  /** 清洗类任务对应的批次；其他任务返回空。 */
  public Optional<UUID> cleaningBatchFor(UUID jobId) {
    return jdbc.sql(
            """
            SELECT cleaning_batch_id FROM job
            WHERE id = :id AND job_type IN ('CLEAN_DATASET', 'SCORE_CLEANING')
            """)
        .param("id", jobId)
        .query(UUID.class)
        .optional();
  }

  public void enqueueAnswer(UUID questionId) {
    jdbc.sql(
            """
            INSERT INTO job (id, job_type, question_id, status)
            VALUES (:id, 'ANSWER_QUESTION', :question, 'QUEUED')
            ON CONFLICT DO NOTHING
            """)
        .param("id", UUID.randomUUID())
        .param("question", questionId)
        .update();
  }

  /** Claim one available job of this type, respecting the shared concurrency cap. */
  @Transactional
  public Job claimNext(Kind kind, int leaseSeconds) {
    lock(kind);
    long running = jdbc.sql("SELECT count(*) FROM job WHERE job_type = :type AND status = 'RUNNING'")
        .param("type", kind.type).query(Long.class).single();
    if (running >= concurrency.get(kind)) {
      return null;
    }
    return jdbc.sql(
            """
            WITH candidate AS (
              SELECT id FROM job
              WHERE job_type = :type AND status = 'QUEUED' AND available_at <= now()
              ORDER BY available_at, id
              LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE job j
            SET status = 'RUNNING', lease_token = :token,
                lease_expires_at = now() + (:secs)::int * interval '1 second',
                attempt_count = attempt_count + 1
            FROM candidate
            WHERE j.id = candidate.id
            RETURNING j.id, j.job_type AS "jobType", j.space_id, j.document_version_id,
                      j.question_id, j.publish_request_id, j.template_generation_id,
                      j.cleaning_batch_id, j.attempt_count, j.lease_token
            """)
        .param("type", kind.type)
        .param("secs", leaseSeconds)
        .param("token", UUID.randomUUID())
        .query(Job.class).optional().orElse(null);
  }

  /** Lock the document job before business writes, excluding expired or superseded claims. */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean lockDocumentLease(Job job) {
    return jdbc.sql("""
            SELECT id FROM job
            WHERE id = :id AND lease_token = :token AND status = 'RUNNING'
              AND job_type IN ('PARSE_DOCUMENT', 'INDEX_DOCUMENT')
              AND lease_expires_at > clock_timestamp()
            FOR UPDATE
            """)
        .param("id", job.id()).param("token", job.leaseToken())
        .query(UUID.class).optional().isPresent();
  }

  public enum DocumentOutcome { SUCCEEDED, FAILED, QUEUED }

  /** Recheck expiry at the final write so the caller can roll back slow business updates. */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean finishDocumentLease(Job job, DocumentOutcome outcome, int delaySeconds, String errorCode) {
    return jdbc.sql("""
            UPDATE job
            SET status = :status, lease_token = NULL, lease_expires_at = NULL, error_code = :error,
                available_at = CASE WHEN :status = 'QUEUED'
                  THEN clock_timestamp() + (:delay)::int * interval '1 second' ELSE available_at END
            WHERE id = :id AND lease_token = :token AND status = 'RUNNING'
              AND job_type IN ('PARSE_DOCUMENT', 'INDEX_DOCUMENT')
              AND lease_expires_at > clock_timestamp()
            """)
        .param("status", outcome.name()).param("error", errorCode).param("delay", delaySeconds)
        .param("id", job.id()).param("token", job.leaseToken())
        .update() == 1;
  }

  public boolean maintainLease(UUID jobId, UUID leaseToken, int leaseSeconds) {
    return jdbc.sql(
            """
            UPDATE job SET lease_expires_at = now() + (:secs)::int * interval '1 second'
            WHERE id = :id AND lease_token = :token AND status = 'RUNNING'
            """)
        .param("secs", leaseSeconds)
        .param("id", jobId)
        .param("token", leaseToken)
        .update() == 1;
  }

  public boolean succeed(UUID jobId, UUID leaseToken) {
    return jdbc.sql(
            """
            UPDATE job SET status = 'SUCCEEDED', lease_token = NULL, lease_expires_at = NULL
            WHERE id = :id AND lease_token = :token AND status = 'RUNNING'
            """)
        .param("id", jobId)
        .param("token", leaseToken)
        .update() == 1;
  }

  public boolean retryLater(UUID jobId, UUID leaseToken, int delaySeconds, String errorCode) {
    return jdbc.sql(
            """
            UPDATE job
            SET status = 'QUEUED', lease_token = NULL, lease_expires_at = NULL,
                available_at = now() + (:delay)::int * interval '1 second', error_code = :error
            WHERE id = :id AND lease_token = :token AND status = 'RUNNING'
            """)
        .param("delay", delaySeconds)
        .param("error", errorCode)
        .param("id", jobId)
        .param("token", leaseToken)
        .update() == 1;
  }

  public boolean fail(UUID jobId, UUID leaseToken, String errorCode) {
    return jdbc.sql(
            """
            UPDATE job SET status = 'FAILED', lease_token = NULL, lease_expires_at = NULL, error_code = :error
            WHERE id = :id AND lease_token = :token AND status = 'RUNNING'
            """)
        .param("error", errorCode)
        .param("id", jobId)
        .param("token", leaseToken)
        .update() == 1;
  }

  public boolean cancel(UUID jobId) {
    return jdbc.sql("UPDATE job SET status = 'CANCELLED' WHERE id = :id AND status = 'QUEUED'")
        .param("id", jobId)
        .update() == 1;
  }

  public Optional<UUID> leaseToken(UUID jobId) {
    return jdbc.sql("SELECT lease_token FROM job WHERE id = :id").param("id", jobId).query(UUID.class).optional();
  }

  public Optional<UUID> publishRequestFor(UUID jobId) {
    return jdbc.sql("SELECT publish_request_id FROM job WHERE id = :id AND job_type = 'PUBLISH_SNAPSHOT'")
        .param("id", jobId).query(UUID.class).optional();
  }

  public Optional<UUID> questionFor(UUID jobId) {
    return jdbc.sql("SELECT question_id FROM job WHERE id = :id AND job_type = 'ANSWER_QUESTION'")
        .param("id", jobId).query(UUID.class).optional();
  }

  public Optional<UUID> documentFor(UUID jobId) {
    return jdbc.sql("SELECT document_version_id FROM job WHERE id = :id")
        .param("id", jobId).query(UUID.class).optional();
  }

  public long pendingByDocument(UUID documentVersionId) {
    return jdbc.sql(
            """
            SELECT count(*) FROM job WHERE document_version_id = :doc
              AND job_type IN ('PARSE_DOCUMENT', 'INDEX_DOCUMENT') AND status IN ('QUEUED', 'RUNNING')
            """)
        .param("doc", documentVersionId)
        .query(Long.class)
        .single();
  }

  public long queuedCount() {
    return jdbc.sql("SELECT count(*) FROM job WHERE status = 'QUEUED'").query(Long.class).single();
  }

  public void requeueExpiredLeases() {
    jdbc.sql(
            """
            UPDATE job SET status = 'QUEUED', lease_token = NULL, lease_expires_at = NULL,
                           available_at = now() + interval '5 seconds'
            WHERE job_type <> 'ANSWER_QUESTION' AND status = 'RUNNING' AND lease_expires_at < now()
            """)
        .update();
  }

  public void failAnswer(UUID jobId, UUID token) {
    jdbc.sql(
            """
            WITH terminated AS (
              UPDATE job SET status = 'FAILED', lease_token = NULL, lease_expires_at = NULL,
                             error_code = 'MODEL_UNAVAILABLE'
              WHERE id = :id AND lease_token = :token AND status = 'RUNNING' AND job_type = 'ANSWER_QUESTION'
              RETURNING question_id
            )
            UPDATE question q SET status = 'FAILED', failure_code = 'MODEL_UNAVAILABLE', finished_at = now()
            FROM terminated t WHERE q.id = t.question_id AND q.status IN ('QUEUED', 'RUNNING')
            """)
        .param("id", jobId).param("token", token).update();
  }

  /** Fail abandoned answers without issuing another model call. Skip an executing transaction. */
  public void failExpiredAnswers() {
    jdbc.sql(
            """
            WITH expired AS (
              SELECT j.id, q.id AS question_id, q.status AS question_status
              FROM job j JOIN question q ON q.id = j.question_id
              WHERE j.job_type = 'ANSWER_QUESTION' AND j.status = 'RUNNING' AND j.lease_expires_at < now()
              FOR UPDATE OF j, q SKIP LOCKED
            ), terminated AS (
              UPDATE job j
              SET status = CASE WHEN e.question_status = 'SUCCEEDED' THEN 'SUCCEEDED' ELSE 'FAILED' END,
                  lease_token = NULL, lease_expires_at = NULL,
                  error_code = CASE WHEN e.question_status = 'SUCCEEDED' THEN NULL ELSE 'MODEL_UNAVAILABLE' END
              FROM expired e WHERE j.id = e.id
              RETURNING e.question_id
            )
            UPDATE question q SET status = 'FAILED', failure_code = 'MODEL_UNAVAILABLE', finished_at = now()
            FROM terminated t WHERE q.id = t.question_id AND q.status IN ('QUEUED', 'RUNNING')
            """)
        .update();
  }

  public void cancelOpenForRequest(UUID requestId) {
    jdbc.sql(
            """
            UPDATE job SET status = 'CANCELLED'
            WHERE job_type = 'PUBLISH_SNAPSHOT' AND publish_request_id = :request
              AND status IN ('QUEUED', 'RUNNING')
            """)
        .param("request", requestId).update();
  }

  public void cancelOpenPublicationsForSpace(UUID spaceId) {
    jdbc.sql(
            """
            UPDATE job SET status = 'CANCELLED'
            WHERE space_id = :space AND status IN ('QUEUED', 'RUNNING')
              AND job_type = 'PUBLISH_SNAPSHOT'
            """)
        .param("space", spaceId).update();
  }

  public record PendingRequest(UUID requestId, UUID spaceId, OffsetDateTime availableAt) {}
}
