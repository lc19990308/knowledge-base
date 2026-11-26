package dev.aiqa.persistence;

import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Idempotency ledger: an operation and key map to one accepted result. Replays return the recorded
 * resource instead of executing again.
 */
@Repository
public class IdempotencyRepository {

  private final JdbcClient jdbc;

  public IdempotencyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record Reservation(UUID resourceId, boolean replay) {}

  /**
   * Reserves the key for this operation. Existing rows with a different digest are rejected; a
   * completed row with the same digest is a replay.
   */
  public Reservation reserve(UUID userId, String operation, String key, String digest) {
    if (key == null || key.isBlank() || key.length() < 3 || key.length() > 255) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST, "缺少或非法 Idempotency-Key");
    }
    int inserted = jdbc.sql(
            """
            INSERT INTO request_idempotency
              (user_id, operation, idempotency_key, request_digest, status, expires_at)
            VALUES (:user, :op, :key, :digest, 'IN_PROGRESS', now() + interval '48 hours')
            ON CONFLICT (user_id, operation, idempotency_key) DO NOTHING
            """)
        .param("user", userId)
        .param("op", operation)
        .param("key", key)
        .param("digest", digest)
        .update();
    if (inserted == 1) {
      return new Reservation(null, false);
    }
    Optional<Row> existing =
        jdbc.sql(
                """
                SELECT request_digest, status, resource_id
                FROM request_idempotency
                WHERE user_id = :user AND operation = :op AND idempotency_key = :key
                """)
            .param("user", userId)
            .param("op", operation)
            .param("key", key)
            .query(Row.class)
            .optional();
    if (existing.isPresent()) {
      Row row = existing.get();
      if (!row.requestDigest().equals(digest)) {
        throw ApiException.conflict(ErrorCode.IDEMPOTENCY_KEY_REUSED);
      }
      if (row.status().equals("COMPLETED")) {
        return new Reservation(row.resourceId(), true);
      }
      throw ApiException.conflict(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
    }
    // A concurrent failed request or expiry cleanup removed the conflicting entry.
    throw ApiException.conflict(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
  }

  public void complete(UUID userId, String operation, String key, UUID resourceId, int status) {
    jdbc.sql(
            """
            UPDATE request_idempotency
            SET status = 'COMPLETED', resource_id = :resource, response_status = :status
            WHERE user_id = :user AND operation = :op AND idempotency_key = :key
            """)
        .param("resource", resourceId)
        .param("status", status)
        .param("user", userId)
        .param("op", operation)
        .param("key", key)
        .update();
  }

  public void release(UUID userId, String operation, String key) {
    jdbc.sql(
            """
            DELETE FROM request_idempotency
            WHERE user_id = :user AND operation = :op AND idempotency_key = :key AND status = 'IN_PROGRESS'
            """)
        .param("user", userId)
        .param("op", operation)
        .param("key", key)
        .update();
  }

  public void purgeExpired() {
    jdbc.sql("DELETE FROM request_idempotency WHERE expires_at < now()").update();
  }

  record Row(String requestDigest, String status, UUID resourceId) {}
}
