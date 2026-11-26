package dev.aiqa.answer;

import dev.aiqa.conversation.ConversationRepository;
import dev.aiqa.conversation.ConversationTitles;
import dev.aiqa.conversation.QuestionRepository;
import dev.aiqa.search.KeywordSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnswerService {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnswerService.class);

  private static final int CANDIDATE_LIMIT = 20;
  /** 向量召回参与排序的候选上限；语料变大后应改为数据库侧近邻检索。 */
  private static final int VECTOR_SCAN_LIMIT = 2000;
  private static final int VECTOR_TOP = 60;

  /**
   * 读取权限的唯一口径：显式成员授权，或系统管理员默认最高权限。
   *
   * <p>与 {@code SpaceRepository.findForUser} 保持同一规则；管理员在空间详情、空间搜索里能看到的内容，
   * 问答取证不能用更窄的条件把它排除掉。这里只放宽"是否可读"，
   * 助手绑定、当前发布、快照与发布代次等约束仍然逐条生效。
   */
  private static final String CAN_READ =
      """
      (COALESCE(m.can_read, FALSE) OR EXISTS (
        SELECT 1 FROM user_role r WHERE r.user_id = :user AND r.role = 'SYSTEM_ADMIN'
      ))
      """;

  private static final String NO_EVIDENCE_TEXT = "当前可访问且已发布的资料中没有能够支持回答的内容。";

  private final JdbcClient jdbc;
  private final KeywordSearch keywords;
  private final QuestionRepository questions;
  private final ConversationRepository conversations;
  private final Map<String, AnswerModel> models;
  private final dev.aiqa.settings.ModelConfigRepository modelConfigs;
  private final io.micrometer.core.instrument.MeterRegistry meters;
  private final TemplateResolver templates;
  private final dev.aiqa.template.AnswerTemplateRepository templateRows;
  private final RelevanceJudge relevance;
  private final dev.aiqa.search.EmbeddingClient embeddings;
  private final AnswerStreams streams;

  public AnswerService(
      JdbcClient jdbc,
      KeywordSearch keywords,
      QuestionRepository questions,
      ConversationRepository conversations,
      Map<String, AnswerModel> models,
      io.micrometer.core.instrument.MeterRegistry meters,
      dev.aiqa.settings.ModelConfigRepository modelConfigs,
      TemplateResolver templates,
      dev.aiqa.template.AnswerTemplateRepository templateRows,
      RelevanceJudge relevance,
      dev.aiqa.search.EmbeddingClient embeddings,
      AnswerStreams streams) {
    this.jdbc = jdbc;
    this.keywords = keywords;
    this.questions = questions;
    this.conversations = conversations;
    this.models = models;
    this.meters = meters;
    this.modelConfigs = modelConfigs;
    this.templates = templates;
    this.templateRows = templateRows;
    this.relevance = relevance;
    this.embeddings = embeddings;
    this.streams = streams;
  }

  public record EvidenceCandidate(
      UUID spaceId, UUID publicationId, long epoch, UUID documentVersionId, String documentName,
      String text, String sourceLocation, PolicyValidity.State validity, String policyState,
      java.time.LocalDate effectiveUntil, int versionNumber, UUID chunkId) {}

  public List<EvidenceCandidate> evidence(UUID questionId, UUID userId, List<UUID> requested) {
    List<UUID> allowed = allowedSpaces(questionId, userId, requested);
    if (allowed.isEmpty()) {
      return List.of();
    }
    String questionText = jdbc.sql("SELECT text FROM question WHERE id = :id")
        .param("id", questionId).query(String.class).optional().orElse("");
    String query = keywords.query(questionText);
    if (query.isEmpty()) {
      return List.of();
    }
    // 启用嵌入时多取一些词法候选，再与向量候选混合；关闭时行为与之前完全一致。
    int lexicalLimit = embeddings.enabled() ? Math.max(60, CANDIDATE_LIMIT * 3) : CANDIDATE_LIMIT;
    List<EvidenceCandidate> lexical = lexicalCandidates(
        questionId, userId, allowed, query, lexicalLimit);
    if (!embeddings.enabled()) {
      return lexical;
    }
    float[] queryVector = embeddings.embed(questionText);
    if (queryVector == null) {
      return lexical;
    }
    List<HybridRanker.VectorCandidate<EvidenceCandidate>> vectors =
        vectorCandidates(questionId, userId, allowed, queryVector);
    if (vectors.isEmpty()) {
      return lexical;
    }
    return HybridRanker.merge(lexical, vectors, queryVector, CANDIDATE_LIMIT,
        EvidenceCandidate::chunkId);
  }

  /** 按关键词取候选，顺序即词法相关度。 */
  private List<EvidenceCandidate> lexicalCandidates(
      UUID questionId, UUID userId, List<UUID> allowed, String query, int limit) {
    List<Map<String, Object>> rows = candidateRows(questionId, userId, allowed, query, limit, false);
    List<EvidenceCandidate> evidence = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      evidence.add(candidate(row));
    }
    return evidence;
  }

  /** 向量候选：同一授权与发布范围里已有向量的文本块，按余弦在内存中排序后截断。 */
  private List<HybridRanker.VectorCandidate<EvidenceCandidate>> vectorCandidates(
      UUID questionId, UUID userId, List<UUID> allowed, float[] queryVector) {
    List<Map<String, Object>> rows =
        candidateRows(questionId, userId, allowed, "", VECTOR_SCAN_LIMIT, true);
    List<HybridRanker.VectorCandidate<EvidenceCandidate>> vectors =
        new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      float[] embedding = toVector(row.get("embedding"));
      if (embedding == null || embedding.length != queryVector.length) {
        continue;
      }
      vectors.add(new HybridRanker.VectorCandidate<>(candidate(row), embedding));
    }
    // 只保留最相似的一批，避免把整个空间的向量都参与排序。
    vectors.sort((left, right) -> Double.compare(
        dev.aiqa.search.EmbeddingClient.cosine(queryVector, right.embedding()),
        dev.aiqa.search.EmbeddingClient.cosine(queryVector, left.embedding())));
    return vectors.size() > VECTOR_TOP ? vectors.subList(0, VECTOR_TOP) : vectors;
  }

  /** 候选查询的公共骨架：授权、当前发布、快照与助手绑定条件完全一致，只切换过滤与排序。 */
  private List<Map<String, Object>> candidateRows(
      UUID questionId, UUID userId, List<UUID> allowed, String query, int limit,
      boolean withEmbedding) {
    String filter = withEmbedding ? "c.embedding IS NOT NULL" : KeywordSearch.MATCH;
    String order = withEmbedding ? "c.id" : KeywordSearch.SCORE + " DESC, s.id, v.id, c.ordinal";
    String sql =
        """
        SELECT s.id AS space_id, pub.id AS publication_id, pub.epoch,
               v.id AS document_id, v.display_filename, c.body, c.source_location,
               sd.policy_state, sd.effective_from, sd.effective_until,
               v.version_number, c.id AS chunk_id, c.embedding
        FROM space s
        JOIN space_publication pub ON pub.id = s.current_publication_id AND pub.space_id = s.id
        JOIN space_snapshot_document sd
          ON sd.snapshot_id = pub.snapshot_id AND sd.space_id = s.id
        JOIN document_version v ON v.id = sd.document_version_id AND v.space_id = s.id
        JOIN document_chunk c ON c.document_version_id = v.id
        LEFT JOIN space_member m ON m.space_id = s.id AND m.user_id = :user
        WHERE s.id IN (:spaces) AND v.parse_state = 'READY'
          AND %s
          AND %s
          AND EXISTS (
             SELECT 1 FROM question q JOIN conversation conv ON conv.id = q.conversation_id
             JOIN assistant_space binding ON binding.assistant_id = conv.assistant_id
             WHERE q.id = :question AND q.user_id = :user AND binding.space_id = s.id
           )
         ORDER BY %s
         LIMIT :limit
         """.formatted(filter, CAN_READ, order);
    var statement = jdbc.sql(sql)
        .param("question", questionId)
        .param("limit", limit)
        .param("user", userId)
        .param("spaces", allowed);
    if (!withEmbedding) {
      statement = statement.param("query", query);
    }
    return statement.query().listOfRows();
  }

  /** 把一行查询结果转成证据候选，政策有效性在此统一折算。 */
  private static EvidenceCandidate candidate(Map<String, Object> row) {
    java.time.LocalDate effectiveFrom = toDate(row.get("effective_from"));
    java.time.LocalDate effectiveUntil = toDate(row.get("effective_until"));
    String policyState = (String) row.get("policy_state");
    return new EvidenceCandidate(
        (UUID) row.get("space_id"), (UUID) row.get("publication_id"),
        ((Number) row.get("epoch")).longValue(), (UUID) row.get("document_id"),
        (String) row.get("display_filename"), (String) row.get("body"),
        (String) row.get("source_location"),
        PolicyValidity.evaluate(policyState, effectiveFrom, effectiveUntil), policyState,
        effectiveUntil, ((Number) row.get("version_number")).intValue(),
        (UUID) row.get("chunk_id"));
  }

  /** PostgreSQL real[] → float[]；类型不符或读取失败时返回 null（该块只参与词法）。 */
  private static float[] toVector(Object value) {
    try {
      Object raw = value instanceof java.sql.Array array ? array.getArray() : value;
      if (!(raw instanceof Object[] items)) {
        return null;
      }
      float[] vector = new float[items.length];
      for (int index = 0; index < items.length; index++) {
        vector[index] = ((Number) items[index]).floatValue();
      }
      return vector;
    } catch (java.sql.SQLException | RuntimeException ex) {
      return null;
    }
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

  /** Assistant bindings ∩ current user read permission ∩ currently published. */
  public List<UUID> allowedSpaces(UUID questionId, UUID userId, List<UUID> requested) {
    List<UUID> allowed =
        jdbc.sql(
                """
                SELECT s.id
                FROM space s
                LEFT JOIN space_member m ON m.space_id = s.id AND m.user_id = :user
                JOIN assistant_space a ON a.space_id = s.id
                JOIN conversation c ON c.assistant_id = a.assistant_id
                WHERE c.id = (SELECT conversation_id FROM question WHERE id = :question)
                  AND s.current_publication_id IS NOT NULL
                  AND %s
                ORDER BY s.id
                """.formatted(CAN_READ))
            .param("user", userId)
            .param("question", questionId)
            .query(UUID.class)
            .list();
    if (requested == null || requested.isEmpty()) {
      return allowed;
    }
    return allowed.stream().filter(requested::contains).toList();
  }

  /**
   * 生成一条回答：取证、判定、调模型、落库。
   *
   * <p>返回 true 表示这次调用确实抢到了执行权；false 表示已被其它 worker 处理或已经结束，
   * 调用方不要据此收尾增量流，否则会把仍在生成的回答误判为结束。
   */
  @Transactional
  public boolean answer(UUID questionId) {
    if (!questions.markRunning(questionId)) {
      return false;
    }
    QuestionRepository.QuestionRow question = questions.findAny(questionId).orElseThrow();
    List<UUID> requested = new ArrayList<>();
    if (question.requestedSpaceIds() != null && !question.requestedSpaceIds().isBlank()) {
      for (String value : question.requestedSpaceIds().split(",")) {
        if (!value.isBlank()) {
          requested.add(UUID.fromString(value.strip()));
        }
      }
    }
    List<UUID> allowed = allowedSpaces(questionId, question.userId(), requested);
    if (requested != null && !requested.isEmpty() && allowed.size() != requested.size()) {
      questions.fail(questionId, "CONTEXT_CHANGED");
      return true;
    }
    List<EvidenceCandidate> candidates = evidence(questionId, question.userId(), requested);
    if (candidates.isEmpty()) {
      returnNoEvidence(questionId, question.conversationId());
      return true;
    }
    List<String> questionTokens = keywords.tokens(question.text());
    EvidenceSupport.Assessment support = EvidenceSupport.assess(
        question.text(),
        questionTokens,
        candidates.stream()
            .map(c -> new EvidenceSupport.Candidate(c.documentName(), c.text()))
            .toList());
    if (!support.sufficient()) {
      // 关键词到但内容不支持所问：按无依据处理，不写引用，避免把顺带出现的片段讲成结论。
      log.info("证据不足 question={} reason={} terms={}",
          questionId, support.reason(), support.negatedOnlyTerms());
      returnNoEvidence(questionId, question.conversationId());
      return true;
    }
    // 覆盖优先重排：复合问题的各个方面都进入靠前候选，而不是被同一文档的相近片段占满。
    List<EvidenceCandidate> ordered =
        EvidenceSupport.rerank(questionTokens, candidates, EvidenceCandidate::text);
    String callId = UUID.randomUUID().toString();
    // The template entity is the single source; an unset or disabled template falls through.
    UUID assistantId =
        jdbc.sql(
                """
                SELECT c.assistant_id
                FROM question q JOIN conversation c ON c.id = q.conversation_id
                WHERE q.id = :question
                """)
            .param("question", questionId)
            .query(UUID.class)
            .single();
    String template =
        templates
            .resolve(templateRows.effectiveBody(assistantId).orElse(null), null)
            .template();
    UUID requestedModelId = question.requestedModelId();
    var gateway = requestedModelId == null
        ? modelConfigs.findDefaultConfig()
            .filter(config -> config.enabled() && !config.baseUrl().isBlank() && !config.modelName().isBlank())
            .orElse(null)
        : modelConfigs.findSelectable(requestedModelId).orElse(null);
    if (requestedModelId != null && gateway == null) {
      questions.fail(questionId, "MODEL_UNAVAILABLE");
      conversations.touch(question.conversationId());
      return true;
    }
    AnswerModel adapter = adapterFor(gateway);
    if (gateway != null) {
      log.debug("使用模型配置 {} ({}) 生成回答 question={}",
          gateway.alias(), gateway.protocol(), questionId);
    }
    // 词法门控判断不了"被问对象不存在"；配置了模型时再做一次语义相关性判定。
    RelevanceJudge.Verdict verdict = relevance.judge(
        gateway == null ? null
            : new RelevanceJudge.Model(
                gateway.protocol(), gateway.baseUrl(), gateway.modelName(), gateway.credentialRef()),
        question.text(),
        ordered.stream().limit(8)
            .map(c -> c.validity().name() + "｜" + c.text())
            .toList());
    if (verdict.inputTokens() != null || verdict.outputTokens() != null) {
      // 判定本身也花 Tokens，单独记一条用量，统计里不吞掉。
      questions.recordUsage(UUID.randomUUID().toString(), questionId, question.userId(),
          verdict.inputTokens(), verdict.outputTokens());
    }
    if (!verdict.sufficient()) {
      log.info("相关性判定为无依据 question={} reason={}", questionId, verdict.reason());
      returnNoEvidence(questionId, question.conversationId());
      return true;
    }
    List<AnswerModel.EvidenceSource> sources =
        ordered.stream()
            .map(c -> new AnswerModel.EvidenceSource(
                c.spaceId().toString(), c.publicationId().toString(), c.epoch(),
                c.documentVersionId().toString(), c.documentName(), c.text(),
                c.validity().name()))
            .toList();
    AnswerModel.Rendered rendered;
    try {
      if (adapter instanceof dev.aiqa.answer.GatewayAnswerModel gatewayModel
          && gateway != null) {
        rendered = gatewayModel.ask(
            gateway.baseUrl(), gateway.modelName(), gateway.credentialRef(),
            question.text(), template, sources,
            delta -> streams.delta(questionId, delta));
      } else {
        rendered = adapter.answer(question.text(), template, sources);
        // 本地替身一次性返回整段：同样按增量下发，前端只走一套打字机渲染。
        streams.delta(questionId, rendered.text());
      }
    } catch (RuntimeException ex) {
      questions.fail(questionId, "MODEL_UNAVAILABLE");
      conversations.touch(question.conversationId());
      log.warn("模型网关调用失败 question={}", questionId, ex);
      return true;
    }
    if (!stillCurrent(ordered, questionId, question.userId())) {
      questions.fail(questionId, "CONTEXT_CHANGED");
      return true;
    }
    // Usage row first: model_call_evidence references the call.
    questions.recordUsage(
        callId, questionId, question.userId(),
        rendered.inputTokens(), rendered.outputTokens());
    if (rendered.inputTokens() == null && rendered.outputTokens() == null) {
      meters.counter("aiqa.model.usage.unreported").increment();
    }
    // 政策有效性决定段落类型：全失效、待核实、或同一文件名多版本并存时冲突。
    String kind = paragraphKind(ordered, rendered.citedEvidenceIndexes());
    UUID paragraphId = questions.insertParagraph(questionId, 0, rendered.text(), kind);
    for (int index = 0; index < ordered.size(); index++) {
      EvidenceCandidate candidate = ordered.get(index);
      questions.insertEvidence(paragraphId, callId, index, candidate.spaceId(),
          candidate.publicationId(), candidate.epoch(), candidate.documentVersionId(),
           candidate.text(), candidate.sourceLocation());
    }
    for (Integer cited : rendered.citedEvidenceIndexes().stream().distinct().toList()) {
      if (cited >= 0 && cited < ordered.size()) {
        EvidenceCandidate candidate = ordered.get(cited);
        questions.insertCitation(paragraphId, callId, cited, candidate.documentName());
      }
    }
    questions.succeed(questionId);
    // 首轮回答完成后按问答上下文补全标题；多轮会话与用户显式命名的标题不覆盖。
    conversations.setGeneratedTitle(
        question.conversationId(), ConversationTitles.fromExchange(question.text(), rendered.text()));
    conversations.touch(question.conversationId());
    return true;
  }

  /**
   * 段落类型由证据的有效性分布决定，优先级：冲突 → 全部失效 → 全部待核实 → 普通回答。
   *
   * <p>只有"全部引用证据都过期"才标 EXPIRED。问答同时引用现行版与历史版时，
   * 整段标成失效会让用户以为当前规则已作废；这种情况下按普通回答返回，
   * 由每条引用的有效期各自标注效力。
   *
   * <p>冲突判定依据可核对的事实：同一文件名在同一快照里存在多个版本，且都处于生效状态。
   */
  private String paragraphKind(List<EvidenceCandidate> candidates, List<Integer> cited) {
    List<EvidenceCandidate> used =
        cited.isEmpty()
            ? candidates
            : cited.stream()
                .filter(index -> index >= 0 && index < candidates.size())
                .map(candidates::get)
                .toList();
    java.util.Map<String, java.util.Set<Integer>> versionsByName = new java.util.HashMap<>();
    for (EvidenceCandidate candidate : used) {
      if (candidate.validity() == PolicyValidity.State.EFFECTIVE) {
        versionsByName
            .computeIfAbsent(candidate.documentName(), key -> new java.util.HashSet<>())
            .add(candidate.versionNumber());
      }
    }
    boolean conflict = versionsByName.values().stream().anyMatch(versions -> versions.size() > 1);
    if (conflict) {
      return "CONFLICT";
    }
    boolean allExpired =
        !used.isEmpty() && used.stream().allMatch(c -> c.validity() == PolicyValidity.State.EXPIRED);
    if (allExpired) {
      return "EXPIRED";
    }
    boolean allUncertain =
        !used.isEmpty() && used.stream().allMatch(c -> c.validity() == PolicyValidity.State.UNCERTAIN);
    return allUncertain ? "UNCERTAIN" : "ANSWER";
  }

  /** 协议 → 适配器 bean 名；未知协议回退 fake。 */
  private AnswerModel adapterFor(dev.aiqa.settings.ModelConfigRepository.ConfigRow gateway) {
    if (gateway == null) {
      return models.get("fakeAnswerModel");
    }
    String bean = switch (gateway.protocol()) {
      case "openai_compatible" -> "openAiCompatibleAnswerModel";
      case "anthropic" -> "anthropicAnswerModel";
      default -> "fakeAnswerModel";
    };
    AnswerModel adapter = models.get(bean);
    return adapter != null ? adapter : models.get("fakeAnswerModel");
  }

  /** 无依据收尾：写一条不带引用的 NO_EVIDENCE 段落，不产生用量与引用记录。 */
  private void returnNoEvidence(UUID questionId, UUID conversationId) {
    questions.insertParagraph(questionId, 0, NO_EVIDENCE_TEXT, "NO_EVIDENCE");
    questions.succeed(questionId);
    conversations.touch(conversationId);
  }

  private boolean stillCurrent(List<EvidenceCandidate> candidates, UUID questionId, UUID userId) {
    for (EvidenceCandidate candidate : candidates) {
      Long matches =
          jdbc.sql(
                  """
                  SELECT count(*) FROM space s
                  JOIN space_publication pub ON pub.id = s.current_publication_id AND pub.space_id = s.id
                  JOIN space_snapshot_document sd
                    ON sd.snapshot_id = pub.snapshot_id AND sd.space_id = s.id
                       AND sd.document_version_id = :doc
                  LEFT JOIN space_member m ON m.space_id = s.id AND m.user_id = :user
                   WHERE s.id = :space AND pub.id = :publication AND pub.epoch = :epoch
                     AND %s
                     AND EXISTS (
                       SELECT 1 FROM question q JOIN conversation conv ON conv.id = q.conversation_id
                       JOIN assistant_space binding ON binding.assistant_id = conv.assistant_id
                       WHERE q.id = :question AND q.user_id = :user AND binding.space_id = s.id
                     )
                  """.formatted(CAN_READ))
              .param("user", userId)
               .param("question", questionId)
               .param("doc", candidate.documentVersionId())
              .param("space", candidate.spaceId())
              .param("publication", candidate.publicationId())
              .param("epoch", candidate.epoch())
              .query(Long.class).single();
      if (matches != 1) {
        return false;
      }
    }
    return true;
  }
}
