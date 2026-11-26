package dev.aiqa.usage;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class UsageService {

  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private final JdbcClient jdbc;

  public UsageService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Hourly buckets for a day, daily buckets for a week or month; same Beijing boundaries. */
  public java.util.List<UsageBucketView> series(UUID userId, String period, LocalDate date) {
    LocalDate start;
    LocalDate endExclusive;
    switch (period) {
      case "DAY" -> {
        start = date;
        endExclusive = date.plusDays(1);
      }
      case "WEEK" -> {
        start = date.minusDays(date.getDayOfWeek().getValue() - 1L);
        endExclusive = start.plusWeeks(1);
      }
      case "MONTH" -> {
        start = date.withDayOfMonth(1);
        endExclusive = start.plusMonths(1);
      }
      default -> throw dev.aiqa.common.ApiException.invalid("period 仅支持 DAY、WEEK、MONTH");
    }
    java.time.OffsetDateTime from = start.atStartOfDay(ZONE).toOffsetDateTime();
    java.time.OffsetDateTime to = endExclusive.atStartOfDay(ZONE).toOffsetDateTime();
    boolean hourly = "DAY".equals(period);

    java.util.List<Map<String, Object>> rows =
        jdbc.sql(
                """
                SELECT date_trunc(:unit, accepted_at AT TIME ZONE 'Asia/Shanghai') AS bucket,
                       count(*) AS questions,
                       count(*) FILTER (WHERE status = 'FAILED') AS failures
                FROM question
                WHERE (CAST(:user AS uuid) IS NULL OR user_id = :user) AND accepted_at >= :from AND accepted_at < :to
                GROUP BY 1 ORDER BY 1
                """)
            .param("unit", hourly ? "hour" : "day")
            .param("user", userId, java.sql.Types.OTHER)
            .param("from", from)
            .param("to", to)
            .query()
            .listOfRows();

    java.util.Map<java.time.LocalDateTime, Map<String, Object>> byBucket = new java.util.HashMap<>();
    for (Map<String, Object> row : rows) {
      java.sql.Timestamp stamp = (java.sql.Timestamp) row.get("bucket");
      byBucket.put(stamp.toLocalDateTime(),
          Map.of("questions", ((Number) row.get("questions")).longValue(),
              "failures", ((Number) row.get("failures")).longValue()));
    }

    java.util.List<UsageBucketView> result = new java.util.ArrayList<>();
    if (hourly) {
      java.time.LocalDateTime cursor = start.atStartOfDay();
      while (cursor.isBefore(endExclusive.atStartOfDay())) {
        Map<String, Object> value = byBucket.getOrDefault(cursor, Map.of("questions", 0L, "failures", 0L));
        result.add(new UsageBucketView(
            String.format("%02d:00", cursor.getHour()), cursor.toString(),
            ((Number) value.get("questions")).longValue(),
            ((Number) value.get("failures")).longValue()));
        cursor = cursor.plusHours(1);
      }
    } else {
      java.time.LocalDate cursor = start;
      while (cursor.isBefore(endExclusive)) {
        Map<String, Object> value =
            byBucket.getOrDefault(cursor.atStartOfDay(), Map.of("questions", 0L, "failures", 0L));
        result.add(new UsageBucketView(
            "MONTH".equals(period) ? cursor.getDayOfMonth() + " 日" : cursor.toString(),
            cursor.toString(),
            ((Number) value.get("questions")).longValue(),
            ((Number) value.get("failures")).longValue()));
        cursor = cursor.plusDays(1);
      }
    }
    return result;
  }

  /** 契约要求 reportedTokenCount 恒在（可为 null）。 */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record UsageView(
      String userId, String period, String periodStart, String periodEndExclusive,
      String timeZone, long conversationCount, long questionCount, long failedQuestionCount,
      Long reportedTokenCount, long unreportedModelCallCount) {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record UsageBucketView(
      String label, String bucketStart, long questionCount, long failedQuestionCount) {}

  /** 管理员视角的用户排行，按提问数倒序。 */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record UserUsageRow(
      String userId, String displayName, long conversationCount, long questionCount,
      long failedQuestionCount, Long reportedTokenCount, long unreportedModelCallCount) {}

  /** 提问明细，供对账使用。 */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record QuestionRowView(
      String questionId, String userId, String acceptedAt, String status, String text,
      Long tokenTotal) {}

  /** Beijing natural day/week/month, Monday-first week, half-open interval. */
  public UsageView summary(UUID userId, String period, LocalDate date) {
    LocalDate start;
    LocalDate endExclusive;
    switch (period) {
      case "DAY" -> {
        start = date;
        endExclusive = date.plusDays(1);
      }
      case "WEEK" -> {
        start = date.minusDays(date.getDayOfWeek().getValue() - 1L);
        endExclusive = start.plusWeeks(1);
      }
      case "MONTH" -> {
        start = date.withDayOfMonth(1);
        endExclusive = start.plusMonths(1);
      }
      default -> throw dev.aiqa.common.ApiException.invalid("period 仅支持 DAY、WEEK、MONTH");
    }
    java.time.OffsetDateTime from = start.atStartOfDay(ZONE).toOffsetDateTime();
    java.time.OffsetDateTime to = endExclusive.atStartOfDay(ZONE).toOffsetDateTime();

    Map<String, Object> row =
        jdbc.sql(
                """
                SELECT
                  count(DISTINCT conversation_id) AS conversations,
                  count(*) AS questions,
                  count(*) FILTER (WHERE status = 'FAILED') AS failures
                FROM question
                WHERE (CAST(:user AS uuid) IS NULL OR user_id = :user) AND accepted_at >= :from AND accepted_at < :to
                """)
            .param("user", userId, java.sql.Types.OTHER).param("from", from).param("to", to)
            .query().singleRow();

    Map<String, Object> tokens = jdbc.sql(
            """
            SELECT sum(COALESCE(m.input_tokens, 0) + COALESCE(m.output_tokens, 0)) FILTER (
                     WHERE m.input_tokens IS NOT NULL OR m.output_tokens IS NOT NULL) AS reported,
                   count(*) FILTER (WHERE m.input_tokens IS NULL AND m.output_tokens IS NULL) AS unreported
            FROM question q JOIN model_call_usage m ON m.question_id = q.id
            WHERE (CAST(:user AS uuid) IS NULL OR q.user_id = :user)
              AND q.accepted_at >= :from AND q.accepted_at < :to
            """)
        .param("user", userId, java.sql.Types.OTHER).param("from", from).param("to", to)
        .query().singleRow();
    Number reported = (Number) tokens.get("reported");
    return new UsageView(
        userId == null ? null : userId.toString(), period, start.toString(), endExclusive.toString(), "Asia/Shanghai",
        ((Number) row.get("conversations")).longValue(),
        ((Number) row.get("questions")).longValue(),
        ((Number) row.get("failures")).longValue(),
        reported == null ? null : reported.longValue(),
        ((Number) tokens.get("unreported")).longValue());
  }

  /** 文档类指标行：空间名、被引用次数、覆盖提问数。 */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record SpaceUsageRow(
      String spaceId, String name, long citationCount, long questionCount) {}

  /** 周期内按用户聚合，仅供系统管理员查看。 */
  public java.util.List<UserUsageRow> ranking(String period, LocalDate date) {
    LocalDate[] window = window(period, date);
    java.time.OffsetDateTime from = window[0].atStartOfDay(ZONE).toOffsetDateTime();
    java.time.OffsetDateTime to = window[1].atStartOfDay(ZONE).toOffsetDateTime();
    return jdbc.sql(
            """
            WITH period_questions AS (
               SELECT id, user_id, conversation_id, status FROM question
               WHERE accepted_at >= :from AND accepted_at < :to
             ), token_totals AS (
               SELECT q.user_id,
                      sum(COALESCE(m.input_tokens, 0) + COALESCE(m.output_tokens, 0)) FILTER (
                        WHERE m.input_tokens IS NOT NULL OR m.output_tokens IS NOT NULL) AS tokens,
                      count(*) FILTER (WHERE m.input_tokens IS NULL AND m.output_tokens IS NULL) AS unreported
               FROM period_questions q JOIN model_call_usage m ON m.question_id = q.id
               GROUP BY q.user_id
             )
             SELECT u.id, u.display_name,
                    count(DISTINCT q.conversation_id) AS conversations,
                    count(q.id) AS questions,
                    count(q.id) FILTER (WHERE q.status = 'FAILED') AS failures,
                    t.tokens, COALESCE(t.unreported, 0) AS unreported
             FROM app_user u JOIN period_questions q ON q.user_id = u.id
             LEFT JOIN token_totals t ON t.user_id = u.id
             GROUP BY u.id, u.display_name, t.tokens, t.unreported
             ORDER BY questions DESC, u.display_name
            """)
        .param("from", from)
        .param("to", to)
        .query()
        .listOfRows()
        .stream()
        .map(row -> new UserUsageRow(
            ((java.util.UUID) row.get("id")).toString(),
            (String) row.get("display_name"),
            ((Number) row.get("conversations")).longValue(),
            ((Number) row.get("questions")).longValue(),
            ((Number) row.get("failures")).longValue(),
            row.get("tokens") == null ? null : ((Number) row.get("tokens")).longValue(),
            ((Number) row.get("unreported")).longValue()))
        .toList();
  }

  /** 周期内提问明细。userId 为空时查询所有用户，由控制器鉴权。 */
  public java.util.List<QuestionRowView> questions(UUID userId, String period, LocalDate date) {
    LocalDate[] window = window(period, date);
    java.time.OffsetDateTime from = window[0].atStartOfDay(ZONE).toOffsetDateTime();
    java.time.OffsetDateTime to = window[1].atStartOfDay(ZONE).toOffsetDateTime();
    return jdbc.sql(
            """
            SELECT q.id, q.user_id, q.accepted_at, q.status, q.text,
                   (SELECT CASE
                             WHEN count(*) FILTER (
                               WHERE m.input_tokens IS NOT NULL OR m.output_tokens IS NOT NULL
                             ) = 0 THEN NULL
                             ELSE sum(COALESCE(m.input_tokens, 0) + COALESCE(m.output_tokens, 0))
                           END
                    FROM model_call_usage m WHERE m.question_id = q.id) AS tokens
            FROM question q
            WHERE (CAST(:user AS uuid) IS NULL OR q.user_id = :user) AND q.accepted_at >= :from AND q.accepted_at < :to
            ORDER BY q.accepted_at DESC, q.id
            LIMIT 200
            """)
        .param("user", userId, java.sql.Types.OTHER)
        .param("from", from)
        .param("to", to)
        .query()
        .listOfRows()
        .stream()
        .map(row -> new QuestionRowView(
            ((java.util.UUID) row.get("id")).toString(),
            ((java.util.UUID) row.get("user_id")).toString(),
            row.get("accepted_at").toString(),
            (String) row.get("status"),
            (String) row.get("text"),
            row.get("tokens") == null ? null : ((Number) row.get("tokens")).longValue()))
        .toList();
  }

  /** 文档类指标按空间统计：周期内各空间被答案依赖的次数与覆盖的提问数。 */
  public java.util.List<SpaceUsageRow> spaceUsage(String period, LocalDate date) {
    LocalDate[] range = window(period, date);
    java.time.OffsetDateTime from = range[0].atStartOfDay(ZONE).toOffsetDateTime();
    java.time.OffsetDateTime to = range[1].atStartOfDay(ZONE).toOffsetDateTime();
    return jdbc.sql(
            """
            SELECT mce.space_id, s.name,
                   count(*) AS citations,
                   count(DISTINCT q.id) AS questions
            FROM paragraph_evidence pe
            JOIN model_call_evidence mce ON mce.call_id = pe.call_id AND mce.ordinal = pe.ordinal
            JOIN answer_paragraph ap ON ap.id = pe.paragraph_id
            JOIN question q ON q.id = ap.question_id
            JOIN space s ON s.id = mce.space_id
            WHERE q.accepted_at >= :from AND q.accepted_at < :to
            GROUP BY mce.space_id, s.name
            ORDER BY citations DESC, s.name
            """)
        .param("from", from)
        .param("to", to)
        .query()
        .listOfRows()
        .stream()
        .map(row -> new SpaceUsageRow(
            ((java.util.UUID) row.get("space_id")).toString(),
            (String) row.get("name"),
            ((Number) row.get("citations")).longValue(),
            ((Number) row.get("questions")).longValue()))
        .toList();
  }

  /** 统一的北京时间自然周期窗口计算，避免各处重复。 */
  private static LocalDate[] window(String period, LocalDate date) {
    return switch (period) {
      case "DAY" -> new LocalDate[] {date, date.plusDays(1)};
      case "WEEK" -> {
        LocalDate start = date.minusDays(date.getDayOfWeek().getValue() - 1L);
        yield new LocalDate[] {start, start.plusWeeks(1)};
      }
      case "MONTH" -> {
        LocalDate start = date.withDayOfMonth(1);
        yield new LocalDate[] {start, start.plusMonths(1)};
      }
      default -> throw dev.aiqa.common.ApiException.invalid("period 仅支持 DAY、WEEK、MONTH");
    };
  }
}
