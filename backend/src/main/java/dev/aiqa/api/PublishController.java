package dev.aiqa.api;

import dev.aiqa.auth.AppUser;
import dev.aiqa.auth.CurrentUser;
import dev.aiqa.common.ApiException;
import dev.aiqa.draft.DraftService;
import dev.aiqa.publish.PublishRepository;
import dev.aiqa.publish.PublishService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PublishController {

  private final CurrentUser current;
  private final PublishService publishes;
  private final DraftService drafts;
  private final dev.aiqa.common.RateLimiter limiter;
  private final dev.aiqa.document.DocumentRepository documents;

  public PublishController(CurrentUser current, PublishService publishes, DraftService drafts,
      dev.aiqa.common.RateLimiter limiter,
      dev.aiqa.document.DocumentRepository documents) {
    this.current = current;
    this.publishes = publishes;
    this.drafts = drafts;
    this.limiter = limiter;
    this.documents = documents;
  }

  public record SubmitInput(String reason) {}

  public record DecisionInput(String decision, String reason) {}

  public record SnapshotEntryView(
      UUID documentVersionId, String name, String policyState, String effectiveFrom,
      String effectiveUntil, UUID replacesDocumentVersionId, String parseState) {}

  /** 契约要求 snapshot（冻结清单）恒在，且它是审核抽屉里「变更内容」的数据来源。 */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record RequestView(
      UUID requestId, UUID spaceId, UUID snapshotId, String status, UUID submittedBy, String submittedAt,
      String submissionReason, String decisionReason, boolean decisionRequired,
      long draftRevision, UUID publicationId, List<SnapshotEntryView> snapshot) {}

  /** Contract fields: draft revision of the frozen snapshot and the resulting publication. */
  private RequestView view(PublishRepository.Request request) {
    return new RequestView(
        request.id(), request.spaceId(), request.snapshotId(), request.status(), request.submittedBy(),
        request.submittedAt() == null ? null : request.submittedAt().toString(),
        request.submissionReason(), request.decisionReason(),
        List.of("PENDING_REVIEW", "APPROVED_WAITING_INDEX").contains(request.status()),
        publishes.draftRevisionOf(request),
        publishes.publicationOf(request).orElse(null),
        snapshotOf(request));
  }

  private List<SnapshotEntryView> snapshotOf(PublishRepository.Request request) {
    return publishes.snapshotEntries(request.spaceId(), request.snapshotId()).stream()
        .map(entry -> new SnapshotEntryView(
            entry.documentVersionId(), entry.displayFilename(), entry.policyState(),
            entry.effectiveFrom() == null ? null : entry.effectiveFrom().toString(),
            entry.effectiveUntil() == null ? null : entry.effectiveUntil().toString(),
            entry.replacesDocumentVersionId(), entry.parseState()))
        .toList();
  }

  @PostMapping("/spaces/{spaceId}/publish-requests")
  public ResponseEntity<RequestView> submit(
      @PathVariable String spaceId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) SubmitInput input) {
    AppUser user = current.require();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ApiException(dev.aiqa.common.ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
    }
    limiter.checkWrites(user.id());
    long expected = Http.requireIfMatch(ifMatch);
    PublishRepository.Request request =
        publishes.submit(user, Http.pathUuid(spaceId, "spaceId"), expected,
            input == null || input.reason() == null || input.reason().isBlank() ? "提交发布审核" : input.reason(),
            idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.LOCATION, "/api/v1/publish-requests/" + request.id())
        .header(HttpHeaders.ETAG, Http.etag(drafts.revision(request.spaceId())))
        .body(view(request));
  }

  /** 发布申请列表：可按状态筛选（对应界面的状态页签），offset/limit 之外的总数放在 total。 */
  @GetMapping("/publish-requests")
  public dev.aiqa.common.PageView<RequestView> listAll(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {
    AppUser user = current.require();
    dev.aiqa.common.PageQuery page = dev.aiqa.common.PageQuery.of(limit, offset);
    List<PublishRepository.Request> rows = publishes.listVisible(user);
    rows = filterStatus(rows, status);
    List<RequestView> items = page.slice(rows).stream().map(this::view).toList();
    return dev.aiqa.common.PageView.of(items, rows.size());
  }

  @GetMapping("/spaces/{spaceId}/publish-requests")
  public dev.aiqa.common.PageView<RequestView> list(
      @PathVariable String spaceId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {
    AppUser user = current.require();
    dev.aiqa.common.PageQuery page = dev.aiqa.common.PageQuery.of(limit, offset);
    List<PublishRepository.Request> rows =
        publishes.list(user, Http.pathUuid(spaceId, "spaceId"));
    rows = filterStatus(rows, status);
    List<RequestView> items = page.slice(rows).stream().map(this::view).toList();
    return dev.aiqa.common.PageView.of(items, rows.size());
  }

  private static final List<String> REQUEST_STATUSES =
      List.of("PENDING_REVIEW", "APPROVED_WAITING_INDEX", "REJECTED", "PUBLISHED", "CANCELLED");

  private static List<PublishRepository.Request> filterStatus(
      List<PublishRepository.Request> rows, String status) {
    if (status == null || status.isBlank()) {
      return rows;
    }
    String wanted = status.strip().toUpperCase(java.util.Locale.ROOT);
    if (!REQUEST_STATUSES.contains(wanted)) {
      throw new ApiException(dev.aiqa.common.ErrorCode.INVALID_REQUEST,
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "status 只支持 " + String.join("、", REQUEST_STATUSES));
    }
    return rows.stream().filter(row -> wanted.equals(row.status())).toList();
  }

  @GetMapping("/publish-requests/{requestId}")
  public ResponseEntity<RequestView> get(@PathVariable String requestId) {
    AppUser user = current.require();
    PublishRepository.Request request = publishes.get(user, Http.pathUuid(requestId, "requestId"));
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, Http.etag(drafts.revision(request.spaceId())))
        .body(view(request));
  }

  /** 契约以 DocumentPreview 声明该响应，因此状态字段名为 status。 */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record ReviewerDocument(
      String documentVersionId, String name, String status, String previewText,
      Long sizeBytes, String mediaType, Integer versionNumber, String errorCode) {}

  @GetMapping("/publish-requests/{requestId}/documents/{documentVersionId}")
  public ReviewerDocument reviewDocument(
      @PathVariable String requestId, @PathVariable String documentVersionId) {
    AppUser user = current.require();
    PublishRepository.Request request = publishes.get(user, Http.pathUuid(requestId, "requestId"));
    UUID document = Http.pathUuid(documentVersionId, "documentVersionId");
    if (!documents.inSnapshot(request.spaceId(), request.snapshotId(), document)) {
      throw ApiException.notFound();
    }
    var row = documents.find(request.spaceId(), document).orElseThrow(ApiException::notFound);
    String text =
        documents.chunks(request.spaceId(), document).stream()
            .map(dev.aiqa.document.DocumentRepository.ChunkRow::body)
            .reduce((left, right) -> left + "\n\n" + right)
            .orElse(null);
    return new ReviewerDocument(
        row.id().toString(), row.displayFilename(), row.parseState(), text,
        row.sizeBytes(), row.mediaType(), row.versionNumber(), row.errorCode());
  }

  @PostMapping("/publish-requests/{requestId}/decision")
  public ResponseEntity<RequestView> decide(
      @PathVariable String requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody DecisionInput input) {
    AppUser user = current.requireAdmin();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ApiException(dev.aiqa.common.ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
    }
    limiter.checkWrites(user.id());
    long expected = Http.requireIfMatch(ifMatch);
    UUID id = Http.pathUuid(requestId, "requestId");
    PublishService.DecisionResult result =
        publishes.decide(user, id, expected, input.decision(), input.reason(), idempotencyKey);
    PublishRepository.Request request =
        publishes.get(user, id);
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, Http.etag(drafts.revision(request.spaceId())))
        .body(view(request));
  }
}
