package dev.aiqa.search;

import dev.aiqa.common.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

  private final JdbcClient jdbc;
  private final KeywordSearch keywords;

  public SearchService(JdbcClient jdbc, KeywordSearch keywords) {
    this.jdbc = jdbc;
    this.keywords = keywords;
  }

  /** 命中内容所在的位置：当前已发布、已撤回的最近一次发布、或未发布的草稿。 */
  public enum Source {
    PUBLISHED,
    WITHDRAWN,
    DRAFT
  }

  /** 一次检索指向的文档集合：快照（snapshotId 非空）或草稿（snapshotId 为空）。 */
  public record Target(Source source, UUID snapshotId) {}

  /** validity 由快照冻结的政策状态与生效区间算出，供界面显示「现行/已失效/待核实」。 */
  public record Hit(
      UUID documentVersionId, String name, String status, String snippet, String match,
      String validity, int versionNumber) {}

  public record Result(String status, List<Hit> items) {}

  private record Publication(UUID id, UUID snapshotId) {}

  /**
   * 解析检索指向的内容来源。普通用户只会走到当前发布快照；系统管理员可用 latest 取最近一次
   * 发布（含已撤回），用 draft 取未发布草稿，auto 则按 当前发布 → 最近发布 → 草稿 依次回退。
   */
  public Target resolveTarget(UUID spaceId, UUID currentPublicationId, String requested) {
    if ("DRAFT".equals(requested)) {
      return new Target(Source.DRAFT, null);
    }
    Optional<Publication> latest = latestPublication(spaceId);
    if (latest.isEmpty()) {
      if ("AUTO".equals(requested)) {
        return new Target(Source.DRAFT, null);
      }
      throw ApiException.notFound();
    }
    Publication publication = latest.get();
    boolean current = publication.id().equals(currentPublicationId);
    if ("PUBLISHED".equals(requested) && !current) {
      throw ApiException.notFound();
    }
    return new Target(current ? Source.PUBLISHED : Source.WITHDRAWN, publication.snapshotId());
  }

  private Optional<Publication> latestPublication(UUID spaceId) {
    return jdbc.sql(
            """
            SELECT id, snapshot_id FROM space_publication
            WHERE space_id = :space ORDER BY epoch DESC LIMIT 1
            """)
        .param("space", spaceId)
        .query(Publication.class)
        .optional();
  }

  /** Name-only and body search both start from the resolved document set. */
  public Result search(UUID spaceId, String query, String field, Target target) {
    String trimmed = query == null ? "" : query.strip();
    if (trimmed.isEmpty()) {
      return new Result("NO_MATCH", List.of());
    }
    boolean includeName = !"TEXT".equals(field);
    boolean includeText = !"NAME".equals(field);
    String like = "%" + trimmed.toLowerCase(Locale.ROOT) + "%";

    Map<UUID, Hit> hits = new LinkedHashMap<>();
    if (includeName) {
      JdbcClient.StatementSpec nameQuery =
          scoped(
              """
              SELECT DISTINCT v.id, v.display_filename, v.parse_state, v.version_number,
                     d.policy_state, d.effective_from, d.effective_until
              %s
              WHERE %s AND lower(v.display_filename) LIKE :like
              ORDER BY v.display_filename, v.id
              """.formatted(documentSet(target), scopePredicate(target)),
              spaceId,
              target);
      for (Map<String, Object> row : nameQuery.param("like", like).query().listOfRows()) {
        UUID id = (UUID) row.get("id");
        hits.put(id, new Hit(id, (String) row.get("display_filename"),
            (String) row.get("parse_state"), null, "NAME",
            validityOf(row), ((Number) row.get("version_number")).intValue()));
      }
    }

    if (includeText) {
      String tsquery = keywords.query(trimmed);
      if (!tsquery.isEmpty()) {
        JdbcClient.StatementSpec textQuery =
            scoped(
                """
                SELECT ranked.*, ts_headline('simple', body, to_tsquery('simple', :query),
                       'StartSel=<mark>, StopSel=</mark>, MaxFragments=1, MaxWords=30') AS snippet
                FROM (
                  SELECT DISTINCT ON (v.id) v.id, v.display_filename, v.parse_state,
                         v.version_number, d.policy_state, d.effective_from, d.effective_until,
                         c.body, %s AS score
                  %s
                  JOIN document_chunk c ON c.document_version_id = v.id
                  WHERE %s AND v.parse_state = 'READY' AND %s
                  ORDER BY v.id, score DESC, c.ordinal
                ) ranked
                ORDER BY score DESC, id
                """
                    .formatted(
                        KeywordSearch.SCORE, documentSet(target), scopePredicate(target), KeywordSearch.MATCH),
                spaceId,
                target);
        for (Map<String, Object> row :
            textQuery.param("query", tsquery).query().listOfRows()) {
          UUID id = (UUID) row.get("id");
          hits.putIfAbsent(id, new Hit(id, (String) row.get("display_filename"),
              (String) row.get("parse_state"), (String) row.get("snippet"), "TEXT",
              validityOf(row), ((Number) row.get("version_number")).intValue()));
        }
      }
    }

    if (hits.isEmpty() && processing(spaceId, target)) {
      return new Result("PROCESSING", List.of());
    }
    return new Result(hits.isEmpty() ? "NO_MATCH" : "READY", new ArrayList<>(hits.values()));
  }

  private boolean processing(UUID spaceId, Target target) {
    Long pending =
        scoped(
                """
                SELECT count(*)
                %s
                WHERE %s AND v.parse_state IN ('QUEUED', 'PROCESSING')
                """.formatted(documentSet(target), scopePredicate(target)),
                spaceId,
                target)
            .query(Long.class)
            .single();
    return pending != null && pending > 0;
  }

  private JdbcClient.StatementSpec scoped(String sql, UUID spaceId, Target target) {
    JdbcClient.StatementSpec spec = jdbc.sql(sql).param("space", spaceId);
    return target.snapshotId() == null ? spec : spec.param("snapshot", target.snapshotId());
  }

  /** 文档集合以 d.space_id / d.document_version_id 暴露，快照与草稿形状一致。 */
  private static String documentSet(Target target) {
    String table =
        target.snapshotId() == null ? "space_draft_document" : "space_snapshot_document";
    return """
        FROM %s d
        JOIN document_version v ON v.id = d.document_version_id AND v.space_id = d.space_id
        """
        .formatted(table);
  }

  private static String scopePredicate(Target target) {
    return target.snapshotId() == null
        ? "d.space_id = :space"
        : "d.space_id = :space AND d.snapshot_id = :snapshot";
  }

  /** 快照里冻结的政策状态 + 生效区间 → 当前有效性。 */
  private static String validityOf(Map<String, Object> row) {
    return dev.aiqa.answer.PolicyValidity.evaluate(
            (String) row.get("policy_state"),
            toDate(row.get("effective_from")),
            toDate(row.get("effective_until")))
        .name();
  }

  private static java.time.LocalDate toDate(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof java.sql.Date date) {
      return date.toLocalDate();
    }
    if (value instanceof java.time.LocalDate date) {
      return date;
    }
    return java.time.LocalDate.parse(value.toString());
  }
}
