package dev.aiqa.api;

import dev.aiqa.answer.AnswerService;
import dev.aiqa.assistant.AssistantRepository;
import dev.aiqa.auth.AppUser;
import dev.aiqa.auth.CurrentUser;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import dev.aiqa.common.RateLimiter;
import dev.aiqa.conversation.ConversationRepository;
import dev.aiqa.conversation.ConversationTitles;
import dev.aiqa.conversation.QuestionRepository;
import dev.aiqa.document.DocumentRepository;
import dev.aiqa.persistence.JobRepository;
import dev.aiqa.space.SpaceRepository;
import dev.aiqa.space.SpaceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
public class QaController {

  private static final String CREATE_CONVERSATION = "CREATE_CONVERSATION";
  private static final String ASK_QUESTION = "ASK_QUESTION";
  private static final String ASK_FIRST_QUESTION = "ASK_FIRST_QUESTION";

  private final CurrentUser current;
  private final AssistantRepository assistants;
  private final ConversationRepository conversations;
  private final QuestionRepository questions;
  private final JobRepository jobs;
  private final AnswerService answers;
  private final SpaceService spaces;
  private final SpaceRepository spaceRepository;
  private final DocumentRepository documents;
  private final dev.aiqa.storage.ObjectStore objects;
  private final dev.aiqa.template.AnswerTemplateRepository templates;
  private final dev.aiqa.persistence.IdempotencyRepository idempotency;
  private final dev.aiqa.common.RateLimiter limiter;
  private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
  private final dev.aiqa.settings.ModelConfigRepository modelConfigs;

  public QaController(
      CurrentUser current,
      AssistantRepository assistants,
      ConversationRepository conversations,
      QuestionRepository questions,
      JobRepository jobs,
      AnswerService answers,
      SpaceService spaces,
      SpaceRepository spaceRepository,
      DocumentRepository documents,
      dev.aiqa.storage.ObjectStore objects,
      dev.aiqa.template.AnswerTemplateRepository templates,
      dev.aiqa.persistence.IdempotencyRepository idempotency,
      dev.aiqa.common.RateLimiter limiter,
      org.springframework.jdbc.core.simple.JdbcClient jdbc,
      dev.aiqa.settings.ModelConfigRepository modelConfigs) {
    this.current = current;
    this.assistants = assistants;
    this.conversations = conversations;
    this.questions = questions;
    this.jobs = jobs;
    this.answers = answers;
    this.spaces = spaces;
    this.spaceRepository = spaceRepository;
    this.documents = documents;
    this.objects = objects;
    this.templates = templates;
    this.idempotency = idempotency;
    this.limiter = limiter;
    this.jdbc = jdbc;
    this.modelConfigs = modelConfigs;
  }

  public record BindingInput(String spaceId, String role) {}

  /** 输出绑定额外带空间名，客户端不必为显示名称再拉全量空间。 */
  public record BindingView(String spaceId, String role, String spaceName) {}

  public record AssistantInput(
      String name, List<BindingInput> bindings, String templateId, Boolean enabled) {}

  public record AssistantView(
      String assistantId, String name, List<BindingView> bindings, boolean enabled) {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record AssistantAdminView(
      String assistantId, String name, List<BindingView> bindings, String templateId,
      String template, boolean enabled) {}

  public record ConversationInput(String assistantId, String title) {}

  /** 首个问题：会话由后端在同一事务内创建，客户端不必先建空会话。 */
  public record FirstQuestionInput(
      String assistantId, String text, List<String> requestedSpaceIds, String modelId) {}

  public record ArchiveInput(JsonNode archived) {}

  public record ConversationView(
      String conversationId, String assistantId, String createdAt, boolean archived, String title) {}

  public record QuestionInput(String text, List<String> requestedSpaceIds, String modelId) {}

  public record QuestionAccepted(String questionId, String status) {}

  public record ConversationQuestionAccepted(
      String questionId, String status, ConversationView conversation) {}

  public record ParagraphView(String id, String status, String kind, String text, List<CitationView> citations, String code) {}

  public record CitationView(String citationId, String label, int marker) {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record QuestionView(
      String questionId, String status, String failureCode, List<ParagraphView> paragraphs,
      Long tokenTotal) {}

  public sealed interface MessageView permits UserMessage, AssistantMessage {}

  public record UserMessage(
      String messageId, String questionId, String role, String createdAt, String text)
      implements MessageView {}

  public record AssistantMessage(
      String messageId, String questionId, String role, String createdAt,
      List<ParagraphView> paragraphs, String status,
      @com.fasterxml.jackson.annotation.JsonInclude(
          com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS) String failureCode)
      implements MessageView {}

  /** A template id is optional; the body is resolved from the template entity. */
  private UUID templateIdOf(String templateId) {
    if (templateId == null || templateId.isBlank()) {
      return null;
    }
    UUID id = Http.pathUuid(templateId, "templateId");
    templates.find(id).orElseThrow(() -> ApiException.invalid("模板不存在"));
    return id;
  }

  private static String describeBindings(List<BindingInput> bindings) {
    if (bindings == null) {
      return "";
    }
    return bindings.stream().map(BindingInput::spaceId).sorted().toList().toString();
  }

  @PostMapping("/assistants")
  public ResponseEntity<AssistantAdminView> createAssistant(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody AssistantInput input) {
    AppUser admin = current.requireAdmin();
    if (input.name() == null || input.name().isBlank()) {
      throw ApiException.invalid("助手名称不能为空");
    }
    String digest = dev.aiqa.common.Digests.sha256Hex(
        "create|" + input.name().strip() + "|" + input.templateId() + "|" + describeBindings(input.bindings()));
    var reservation = idempotency.reserve(admin.id(), "CREATE_ASSISTANT", idempotencyKey, digest);
    if (reservation.replay()) {
      return ResponseEntity.status(HttpStatus.CREATED)
          .header(HttpHeaders.LOCATION, "/api/v1/assistants/" + reservation.resourceId())
          .header(HttpHeaders.ETAG, Http.etag(assistants.revision(reservation.resourceId())))
          .body(adminView(reservation.resourceId()));
    }
    UUID id;
    try {
      id = assistants.create(input.name().strip(), templateIdOf(input.templateId()));
      replaceBindings(admin, id, input.bindings());
      idempotency.complete(admin.id(), "CREATE_ASSISTANT", idempotencyKey, id, 201);
    } catch (RuntimeException ex) {
      idempotency.release(admin.id(), "CREATE_ASSISTANT", idempotencyKey);
      throw ex;
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.LOCATION, "/api/v1/assistants/" + id)
        .header(HttpHeaders.ETAG, Http.etag(assistants.revision(id)))
        .body(adminView(id));
  }

  @GetMapping("/assistants")
  public Map<String, Object> listAssistants(
      @RequestParam(name = "management", defaultValue = "false") boolean management,
      @RequestParam(name = "q", required = false, defaultValue = "") String query,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {
    dev.aiqa.common.PageQuery page = dev.aiqa.common.PageQuery.of(limit, offset);
    String name = query == null ? "" : query.strip();
    if (management) {
      current.requireAdmin();
      List<AssistantRepository.AssistantRow> rows = assistants.listAll();
      if (!name.isEmpty()) {
        rows = rows.stream()
            .filter(row -> row.name() != null && row.name().contains(name))
            .toList();
      }
      return Map.of(
          "items", page.slice(rows).stream().map(row -> assistantView(row.id())).toList(),
          "total", rows.size());
    }
    AppUser user = current.require();
    // 管理员需要看到已停用的助手才能重新启用；普通用户只看到启用中的。
    List<AssistantRepository.AssistantRow> rows =
        user.isSystemAdmin() ? assistants.listAll() : assistants.list();
    if (!name.isEmpty()) {
      rows = rows.stream()
          .filter(row -> row.name() != null && row.name().contains(name))
          .toList();
    }
    List<AssistantView> visible =
        rows.stream()
            .filter(row -> user.isSystemAdmin() || row.enabled())
            .map(row -> new AssistantView(
                row.id().toString(), row.name(), visibleBindings(user, row.id()), row.enabled()))
            .filter(view -> !view.bindings().isEmpty())
            .toList();
    return Map.of("items", page.slice(visible), "total", visible.size());
  }

  @GetMapping("/assistants/{assistantId}")
  public ResponseEntity<AssistantAdminView> getAssistant(@PathVariable String assistantId) {
    current.requireAdmin();
    UUID id = Http.pathUuid(assistantId, "assistantId");
    assistants.find(id).orElseThrow(ApiException::notFound);
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, Http.etag(assistants.revision(id)))
        .body(adminView(id));
  }

  @PutMapping("/assistants/{assistantId}")
  public ResponseEntity<AssistantAdminView> updateAssistant(
      @PathVariable String assistantId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody AssistantInput input) {
    AppUser admin = current.requireAdmin();
    UUID id = Http.pathUuid(assistantId, "assistantId");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
    }
    long expected = Http.requireIfMatch(ifMatch);
    assistants.find(id).orElseThrow(ApiException::notFound);
    if (assistants.revision(id) != expected) {
      throw ApiException.preconditionFailed(ErrorCode.ETAG_MISMATCH);
    }
    String digest = dev.aiqa.common.Digests.sha256Hex(
        "update|" + id + "|" + expected + "|" + input.name().strip() + "|" + input.templateId() + "|"
            + describeBindings(input.bindings()));
    var reservation = idempotency.reserve(admin.id(), "UPDATE_ASSISTANT", idempotencyKey, digest);
    if (reservation.replay()) {
      return ResponseEntity.ok().header(HttpHeaders.ETAG, Http.etag(assistants.revision(id))).body(adminView(id));
    }
    try {
      assistants.update(
          id, input.name().strip(), templateIdOf(input.templateId()), input.enabled(), expected);
      replaceBindings(admin, id, input.bindings());
      idempotency.complete(admin.id(), "UPDATE_ASSISTANT", idempotencyKey, id, 200);
    } catch (RuntimeException ex) {
      idempotency.release(admin.id(), "UPDATE_ASSISTANT", idempotencyKey);
      throw ex;
    }
    return ResponseEntity.ok().header(HttpHeaders.ETAG, Http.etag(assistants.revision(id))).body(adminView(id));
  }

  private AssistantAdminView adminView(UUID assistantId) {
    AssistantRepository.AssistantRow row =
        assistants.find(assistantId).orElseThrow(ApiException::notFound);
    return new AssistantAdminView(
        row.id().toString(), row.name(), bindingViews(assistantId),
        row.templateId() == null ? null : row.templateId().toString(), row.template(), row.enabled());
  }

  private List<BindingView> bindingViews(UUID assistantId) {
    return assistants.boundSpaces(assistantId).stream()
        .map(space -> new BindingView(space.id().toString(), "RETRIEVAL", space.name()))
        .toList();
  }

  private void replaceBindings(AppUser admin, UUID assistantId, List<BindingInput> bindings) {
    List<UUID> spaceIds = new ArrayList<>();
    if (bindings != null) {
      for (BindingInput binding : bindings) {
        UUID spaceId = Http.pathUuid(binding.spaceId(), "spaceId");
        spaces.require(spaceId, admin.id());
        spaceIds.add(spaceId);
      }
    }
    assistants.replaceBindings(assistantId, spaceIds.stream().distinct().toList());
  }

  private List<BindingView> visibleBindings(AppUser user, UUID assistantId) {
    return assistants.boundSpaces(assistantId).stream()
        .filter(space -> spaceRows().findForUser(space.id(), user.id())
            .map(SpaceRepository.SpaceRow::isReadable).orElse(false))
        .map(space -> new BindingView(space.id().toString(), "RETRIEVAL", space.name()))
        .toList();
  }

  private SpaceRepository spaceRows() {
    return spaceRepository;
  }

  private AssistantView assistantView(UUID id) {
    AssistantRepository.AssistantRow row = assistants.find(id).orElseThrow(ApiException::notFound);
    return new AssistantView(row.id().toString(), row.name(), bindingViews(id), row.enabled());
  }

  @GetMapping("/conversations")
  public dev.aiqa.common.PageView<ConversationView> listConversations(
      @RequestParam(name = "archived", defaultValue = "false") boolean archived,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {
    AppUser user = current.require();
    dev.aiqa.common.PageQuery page = dev.aiqa.common.PageQuery.of(limit, offset);
    List<ConversationRepository.ConversationRow> rows =
        conversations.listForOwner(user.id(), archived);
    List<ConversationView> items = page.slice(rows).stream()
        .map(QaController::conversationView)
        .toList();
    return dev.aiqa.common.PageView.of(items, rows.size());
  }

  /** 单个会话：分页后侧栏可能拿不到当前会话，用它补齐标题、归属助手和归档状态。 */
  @GetMapping("/conversations/{conversationId}")
  public ConversationView getConversation(@PathVariable String conversationId) {
    AppUser user = current.require();
    UUID id = Http.pathUuid(conversationId, "conversationId");
    return conversationView(conversations.findForOwner(id, user.id()).orElseThrow(ApiException::notFound));
  }

  @PostMapping("/conversations")
  @Transactional
  public ResponseEntity<ConversationView> createConversation(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody ConversationInput input) {
    AppUser user = current.require();
    UUID requestedAssistant = input.assistantId() == null || input.assistantId().isBlank()
        ? null : Http.pathUuid(input.assistantId(), "assistantId");
    String title = input.title() == null ? null : input.title().strip();
    if (title != null && title.codePointCount(0, title.length()) > 240) {
      throw ApiException.invalid("会话标题不能超过 240 个字符");
    }
    // Keep default-assistant requests stable if the default changes before replay.
    String assistantKey = requestedAssistant == null ? "default" : requestedAssistant.toString();
    String digest = dev.aiqa.common.Digests.sha256Hex(
        assistantKey + "|" + (title == null ? "0" : "1" + title));
    var reservation = idempotency.reserve(user.id(), CREATE_CONVERSATION, idempotencyKey, digest);
    UUID id = reservation.resourceId();
    if (!reservation.replay()) {
      UUID assistantId = requestedAssistant == null
          ? assistants.anyEnabled().orElseThrow(() -> ApiException.invalid("没有可用助手"))
          : requestedAssistant;
      assistants.find(assistantId).orElseThrow(ApiException::notFound);
      limiter.checkWrites(user.id());
      id = conversations.create(user.id(), assistantId, title, title == null);
      idempotency.complete(user.id(), CREATE_CONVERSATION, idempotencyKey, id, 201);
    }
    var row = conversations.findForOwner(id, user.id()).orElseThrow(ApiException::notFound);
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.LOCATION, "/api/v1/conversations/" + row.id())
        .body(conversationView(row));
  }

  @PutMapping("/conversations/{conversationId}/archive")
  public ConversationView archiveConversation(
      @PathVariable String conversationId, @RequestBody ArchiveInput input) {
    AppUser user = current.require();
    UUID id = Http.pathUuid(conversationId, "conversationId");
    if (input.archived() == null || !input.archived().isBoolean()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, "archived 必须为布尔值");
    }
    return conversationView(conversations.setArchived(id, user.id(), input.archived().asBoolean())
        .orElseThrow(ApiException::notFound));
  }

  @DeleteMapping("/conversations/{conversationId}")
  public ResponseEntity<Void> deleteConversation(@PathVariable String conversationId) {
    AppUser user = current.require();
    UUID id = Http.pathUuid(conversationId, "conversationId");
    if (!conversations.softDelete(id, user.id())) {
      throw ApiException.notFound();
    }
    return ResponseEntity.noContent().build();
  }

  private static ConversationView conversationView(ConversationRepository.ConversationRow row) {
    return new ConversationView(row.id().toString(), row.assistantId().toString(),
        row.createdAt().toString(), row.archived(), row.title());
  }

  @GetMapping("/conversations/{conversationId}/messages")
  public dev.aiqa.common.PageView<MessageView> messages(@PathVariable String conversationId) {
    AppUser user = current.require();
    UUID conversation = Http.pathUuid(conversationId, "conversationId");
    conversations.findForOwner(conversation, user.id()).orElseThrow(ApiException::notFound);
    List<MessageView> items = new ArrayList<>();
    for (QuestionRepository.QuestionRow question : questions.listByConversation(conversation, user.id())) {
      items.add(new UserMessage(question.id() + "-q", question.id().toString(), "USER",
          question.acceptedAt().toString(), question.text()));
      items.add(new AssistantMessage(question.id() + "-a", question.id().toString(), "ASSISTANT",
          question.acceptedAt().toString(), paragraphs(question.id(), user.id()),
          question.status(), question.failureCode()));
    }
    return dev.aiqa.common.PageView.of(items);
  }

  @PostMapping("/conversations/{conversationId}/questions")
  @Transactional
  public ResponseEntity<QuestionAccepted> ask(
      @PathVariable String conversationId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody QuestionInput input) {
    AppUser user = current.require();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
    }
    UUID conversation = Http.pathUuid(conversationId, "conversationId");
    var row = conversations.lockForOwner(conversation, user.id()).orElseThrow(ApiException::notFound);
    if (row.archived()) {
      throw ApiException.cancel("会话已归档，恢复后才能继续提问");
    }
    String text = input.text() == null ? "" : input.text().strip();
    if (text.isEmpty() || text.length() > 2000) {
      throw ApiException.invalid("问题必须为 1–2000 个字符");
    }
    List<UUID> requested = requestedSpaces(user, input.requestedSpaceIds());
    UUID modelId = input.modelId() == null ? null : Http.pathUuid(input.modelId(), "modelId");
    String digest = dev.aiqa.common.Digests.sha256Hex(
        conversation + "|" + text + "|" + requested + (modelId == null ? "" : "|" + modelId));
    var reservation = idempotency.reserve(user.id(), ASK_QUESTION, idempotencyKey, digest);
    UUID questionId;
    if (reservation.replay()) {
      questionId = reservation.resourceId();
    } else {
      if (modelId != null && modelConfigs.findSelectable(modelId).isEmpty()) {
        throw ApiException.invalid("所选模型不可用，请重新选择");
      }
      jobs.requireCapacity(dev.aiqa.persistence.JobRepository.Kind.ANSWER, 1);
      limiter.checkQuestions(user.id());
      questionId = questions.insert(conversation, user.id(), idempotencyKey, text, requested, modelId);
      jobs.enqueueAnswer(questionId);
      // 旧链路可能先建了空会话；首个问题受理时补上可读标题。
      conversations.setGeneratedTitle(conversation, ConversationTitles.fromQuestion(text));
      idempotency.complete(user.id(), ASK_QUESTION, idempotencyKey, questionId, 202);
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header(HttpHeaders.LOCATION, "/api/v1/questions/" + questionId)
        .body(new QuestionAccepted(questionId.toString(), "QUEUED"));
  }

  /**
   * 首个问题：同一事务内建会话、受理问题并入队，避免客户端先创建一个空会话。
   *
   * <p>标题由问题提炼（问题过短时在首轮回答完成后改用回答上下文），超长截断并加省略号。
   */
  @PostMapping("/questions")
  @Transactional
  public ResponseEntity<ConversationQuestionAccepted> askFirstQuestion(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody FirstQuestionInput input) {
    AppUser user = current.require();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
    }
    String text = input.text() == null ? "" : input.text().strip();
    if (text.isEmpty() || text.length() > 2000) {
      throw ApiException.invalid("问题必须为 1–2000 个字符");
    }
    UUID assistantId = input.assistantId() == null || input.assistantId().isBlank()
        ? assistants.anyEnabled().orElseThrow(() -> ApiException.invalid("没有可用助手"))
        : Http.pathUuid(input.assistantId(), "assistantId");
    assistants.find(assistantId).orElseThrow(ApiException::notFound);
    List<UUID> requested = requestedSpaces(user, input.requestedSpaceIds());
    UUID modelId = input.modelId() == null || input.modelId().isBlank()
        ? null : Http.pathUuid(input.modelId(), "modelId");
    String digest = dev.aiqa.common.Digests.sha256Hex(
        "first|" + assistantId + "|" + text + "|" + requested
            + (modelId == null ? "" : "|" + modelId));
    var reservation = idempotency.reserve(user.id(), ASK_FIRST_QUESTION, idempotencyKey, digest);
    UUID questionId;
    UUID conversationId;
    if (reservation.replay()) {
      questionId = reservation.resourceId();
      conversationId =
          questions.find(questionId, user.id())
              .map(QuestionRepository.QuestionRow::conversationId)
              .orElseThrow(ApiException::notFound);
    } else {
      if (modelId != null && modelConfigs.findSelectable(modelId).isEmpty()) {
        throw ApiException.invalid("所选模型不可用，请重新选择");
      }
      jobs.requireCapacity(dev.aiqa.persistence.JobRepository.Kind.ANSWER, 1);
      limiter.checkQuestions(user.id());
      conversationId =
          conversations.create(
              user.id(), assistantId, ConversationTitles.fromQuestion(text), true);
      questionId = questions.insert(conversationId, user.id(), idempotencyKey, text, requested, modelId);
      jobs.enqueueAnswer(questionId);
      idempotency.complete(user.id(), ASK_FIRST_QUESTION, idempotencyKey, questionId, 202);
    }
    var conversation =
        conversations.findForOwner(conversationId, user.id()).orElseThrow(ApiException::notFound);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header(HttpHeaders.LOCATION, "/api/v1/questions/" + questionId)
        .body(new ConversationQuestionAccepted(
            questionId.toString(), "QUEUED", conversationView(conversation)));
  }

  /** 校验显式空间范围：先解析全部 ID，再逐个校验可读，避免部分受理。 */
  private List<UUID> requestedSpaces(AppUser user, List<String> requestedSpaceIds) {
    List<UUID> requested = new ArrayList<>();
    if (requestedSpaceIds == null) {
      return requested;
    }
    for (String value : requestedSpaceIds) {
      requested.add(Http.pathUuid(value, "requestedSpaceIds"));
    }
    if (requested.isEmpty()) {
      throw ApiException.invalid("requestedSpaceIds 不能为空数组");
    }
    for (UUID spaceId : requested) {
      spaceRows().findForUser(spaceId, user.id())
          .filter(SpaceRepository.SpaceRow::isReadable)
          .orElseThrow(ApiException::notFound);
    }
    return requested;
  }

  @GetMapping("/questions/{questionId}")
  public QuestionView question(@PathVariable String questionId) {
    AppUser user = current.require();
    UUID id = Http.pathUuid(questionId, "questionId");
    QuestionRepository.QuestionRow question = questions.find(id, user.id()).orElseThrow(ApiException::notFound);
    return limiter.read(RateLimiter.Read.STATUS, user.id(), () -> questionView(question, user.id()));
  }

  private QuestionView questionView(QuestionRepository.QuestionRow question, UUID userId) {
    List<ParagraphView> paragraphs = paragraphs(question.id(), userId);
    Long tokenTotal =
        jdbc.sql(
                """
                SELECT CASE
                         WHEN count(*) FILTER (
                           WHERE input_tokens IS NOT NULL OR output_tokens IS NOT NULL
                         ) = 0 THEN NULL
                         ELSE sum(COALESCE(input_tokens, 0) + COALESCE(output_tokens, 0))
                       END
                FROM model_call_usage WHERE question_id = :id
                """)
            .param("id", question.id())
            .query(Long.class)
            .optional()
            .orElse(null);
    return new QuestionView(
        question.id().toString(), question.status(), question.failureCode(), paragraphs, tokenTotal);
  }

  public record CitationDocument(
      String citationId, String name, String previewText, String sourceLocation,
      String documentVersionId, int versionNumber, String validity, String evidenceGranularity) {}

  @GetMapping("/citations/{citationId}")
  public CitationDocument citation(@PathVariable String citationId) {
    AppUser user = current.require();
    QuestionRepository.CitationTarget target =
        questions.citationTarget(Http.pathUuid(citationId, "citationId")).orElseThrow(ApiException::notFound);
    if (!target.ownerId().equals(user.id())) {
      throw ApiException.notFound();
    }
    if (!questions.paragraphStillAuthorized(target.paragraphId(), user.id())) {
      throw ApiException.notFound();
    }
    DocumentRepository.DocumentRow row =
        documents.find(target.spaceId(), target.documentVersionId()).orElseThrow(ApiException::notFound);
    boolean exactExcerpt = target.excerpt() != null;
    String text = exactExcerpt ? target.excerpt() : documents.chunks(row.spaceId(), row.id()).stream()
        .map(DocumentRepository.ChunkRow::body)
        .collect(java.util.stream.Collectors.joining("\n\n"));
    String validity = dev.aiqa.answer.PolicyValidity.evaluate(
        target.policyState(), target.effectiveFrom(), target.effectiveUntil()).name();
    return new CitationDocument(citationId, row.displayFilename(), text, target.sourceLocation(),
        row.id().toString(), row.versionNumber(), validity, exactExcerpt ? "CHUNK" : "DOCUMENT");
  }

  @GetMapping("/citations/{citationId}/content")
  public org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource>
      citationContent(@PathVariable String citationId) throws java.io.IOException {
    AppUser user = current.require();
    QuestionRepository.CitationTarget target =
        questions.citationTarget(Http.pathUuid(citationId, "citationId")).orElseThrow(ApiException::notFound);
    if (!target.ownerId().equals(user.id())) {
      throw ApiException.notFound();
    }
    if (!questions.paragraphStillAuthorized(target.paragraphId(), user.id())) {
      throw ApiException.notFound();
    }
    DocumentRepository.DocumentRow row =
        documents.find(target.spaceId(), target.documentVersionId()).orElseThrow(ApiException::notFound);
    java.io.InputStream stream = objects.get(row.objectKey());
    return org.springframework.http.ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
        .header(
            org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename*=UTF-8''"
                + java.net.URLEncoder.encode(row.displayFilename(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20"))
        .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(new org.springframework.core.io.InputStreamResource(stream));
  }

  private List<ParagraphView> paragraphs(UUID questionId, UUID userId) {
    List<ParagraphView> views = new ArrayList<>();
    Map<UUID, List<CitationView>> citations = new java.util.HashMap<>();
    for (QuestionRepository.CitationRow citation : questions.citations(questionId)) {
      citations.computeIfAbsent(citation.paragraphId(), key -> new ArrayList<>())
          .add(new CitationView(citation.id().toString(), citation.label(), citation.marker()));
    }
    for (QuestionRepository.ParagraphRow paragraph : questions.paragraphs(questionId)) {
      if (!questions.paragraphStillAuthorized(paragraph.id(), userId)) {
        views.add(new ParagraphView(paragraph.id().toString(), "MASKED", null, null, null, "SOURCE_UNAVAILABLE"));
      } else {
        views.add(new ParagraphView(paragraph.id().toString(), "VISIBLE", paragraph.kind(), paragraph.body(),
            citations.getOrDefault(paragraph.id(), List.of()), null));
      }
    }
    return views;
  }

}
