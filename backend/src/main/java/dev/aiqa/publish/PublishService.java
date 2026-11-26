package dev.aiqa.publish;

import dev.aiqa.auth.AppUser;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.Digests;
import dev.aiqa.common.ErrorCode;
import dev.aiqa.draft.DraftRepository;
import dev.aiqa.persistence.JobRepository;
import dev.aiqa.space.SpaceRepository;
import dev.aiqa.space.SpaceService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublishService {

  private final PublishRepository publishes;
  private final DraftRepository drafts;
  private final SpaceService spaces;
  private final SpaceRepository spaceRows;
  private final JobRepository jobs;
  private final AuditRepository audit;
  private final dev.aiqa.persistence.IdempotencyRepository idempotency;

  public PublishService(
      PublishRepository publishes,
      DraftRepository drafts,
      SpaceService spaces,
      SpaceRepository spaceRows,
      JobRepository jobs,
      AuditRepository audit,
      dev.aiqa.persistence.IdempotencyRepository idempotency) {
    this.publishes = publishes;
    this.drafts = drafts;
    this.spaces = spaces;
    this.spaceRows = spaceRows;
    this.jobs = jobs;
    this.audit = audit;
    this.idempotency = idempotency;
  }

  @Transactional
  public PublishRepository.Request submit(
      AppUser user, UUID spaceId, long expectedDraftRevision, String reason, String idempotencyKey) {
    spaces.requireMaintain(spaceId, user.id());
    String digest = Digests.sha256Hex(spaceId + "|" + expectedDraftRevision + "|" + reason);
    var reservation = idempotency.reserve(user.id(), "SUBMIT_PUBLISH_REQUEST", idempotencyKey, digest);
    if (reservation.replay()) {
      return publishes.findRequest(reservation.resourceId()).orElseThrow(ApiException::notFound);
    }
    try {
      PublishRepository.Request request = submitLocked(user, spaceId, expectedDraftRevision, reason);
      idempotency.complete(user.id(), "SUBMIT_PUBLISH_REQUEST", idempotencyKey, request.id(), 201);
      return request;
    } catch (RuntimeException ex) {
      idempotency.release(user.id(), "SUBMIT_PUBLISH_REQUEST", idempotencyKey);
      throw ex;
    }
  }

  private PublishRepository.Request submitLocked(
      AppUser user, UUID spaceId, long expectedDraftRevision, String reason) {
    spaceRows.lockSpace(spaceId);
    long currentRevision = drafts.snapshotRevision(spaceId);
    if (currentRevision != expectedDraftRevision) {
      throw ApiException.preconditionFailed(ErrorCode.ETAG_MISMATCH);
    }
    if (publishes.findOpen(spaceId).isPresent()) {
      throw ApiException.conflict(ErrorCode.PUBLISH_REQUEST_OPEN);
    }
    List<DraftRepository.Entry> entries = drafts.entries(spaceId);
    if (entries.isEmpty()) {
      throw ApiException.invalid("草稿为空，不能提交发布审核");
    }
    String digest =
        Digests.sha256Hex(
            entries.stream()
                .sorted(java.util.Comparator.comparing(entry -> entry.documentVersionId().toString()))
                .map(entry -> entry.documentVersionId() + "|" + entry.effectiveFrom() + "|"
                    + entry.effectiveUntil() + "|" + entry.policyState() + "|" + entry.replacesDocumentVersionId())
                .collect(java.util.stream.Collectors.joining("\n")));
    UUID snapshotId = publishes.createSnapshot(spaceId, currentRevision, digest, user.id());
    UUID basePublication = publishes.currentPublication(spaceId).orElse(null);
    long baseEpoch = publishes.epoch(spaceId);
    UUID requestId =
        publishes.createRequest(spaceId, snapshotId, basePublication, baseEpoch, user.id(), reason);
    audit.record(user.id(), "PUBLISH_REQUEST_SUBMITTED", "publish_request", requestId,
        java.util.Map.of("spaceId", spaceId.toString(), "snapshotId", snapshotId.toString()));
    return publishes.findRequest(requestId).orElseThrow(ApiException::notFound);
  }

  public List<PublishRepository.Request> list(AppUser user, UUID spaceId) {
    spaces.requireMaintain(spaceId, user.id());
    return publishes.listRequests(spaceId);
  }

  public long draftRevisionOf(PublishRepository.Request request) {
    return publishes.draftRevisionOfSnapshot(request.spaceId(), request.snapshotId());
  }

  public java.util.Optional<UUID> publicationOf(PublishRepository.Request request) {
    return publishes.publicationOfRequest(request.id());
  }

  public List<PublishRepository.Request> listVisible(AppUser user) {
    return publishes.listVisible(user.id(), user.isSystemAdmin());
  }

  public PublishRepository.Request get(AppUser user, UUID requestId) {
    PublishRepository.Request request =
        publishes.findRequest(requestId).orElseThrow(ApiException::notFound);
    requireCanSee(user, request);
    return request;
  }

  private void requireCanSee(AppUser user, PublishRepository.Request request) {
    if (user.isSystemAdmin()) {
      return;
    }
    boolean submitter = request.submittedBy().equals(user.id());
    boolean maintainer = spaceRows.membership(request.spaceId(), user.id())
        .map(SpaceRepository.Membership::canMaintain).orElse(false);
    if (!submitter && !maintainer) {
      throw ApiException.notFound();
    }
  }

  /** 供接口层拼装冻结清单，不额外做权限判断（调用方已校验）。 */
  public List<PublishRepository.SnapshotEntry> snapshotEntries(UUID spaceId, UUID snapshotId) {
    return publishes.snapshotEntries(spaceId, snapshotId);
  }

  public List<PublishRepository.SnapshotEntry> snapshot(AppUser user, UUID requestId) {
    PublishRepository.Request request = get(user, requestId);
    return publishes.snapshotEntries(request.spaceId(), request.snapshotId());
  }

  @Transactional
  public DecisionResult decide(
      AppUser reviewer, UUID requestId, long expectedRevision, String decision, String reason,
      String idempotencyKey) {
    if (!reviewer.isSystemAdmin()) {
      throw ApiException.forbidden();
    }
    if (!List.of("APPROVE", "REJECT").contains(decision)) {
      throw ApiException.invalid("decision 非法");
    }
    String text = reason == null ? "" : reason.strip();
    if ("REJECT".equals(decision) && text.isEmpty()) {
      throw ApiException.invalid("驳回必须填写具体原因");
    }
    String digest = Digests.sha256Hex(requestId + "|" + expectedRevision + "|" + decision + "|" + text);
    var reservation = idempotency.reserve(reviewer.id(), "DECIDE_PUBLISH_REQUEST", idempotencyKey, digest);
    if (reservation.replay()) {
      return replayDecision(requestId);
    }
    try {
      DecisionResult result = decideLocked(reviewer, requestId, expectedRevision, decision, text);
      idempotency.complete(reviewer.id(), "DECIDE_PUBLISH_REQUEST", idempotencyKey, requestId, 200);
      return result;
    } catch (RuntimeException ex) {
      idempotency.release(reviewer.id(), "DECIDE_PUBLISH_REQUEST", idempotencyKey);
      throw ex;
    }
  }

  private DecisionResult replayDecision(UUID requestId) {
    PublishRepository.Request request =
        publishes.findRequest(requestId).orElseThrow(ApiException::notFound);
    UUID publicationId =
        publishes.publications(request.spaceId()).stream()
            .filter(row -> row.id() != null && row.epoch() == request.baseEpoch() + 1)
            .map(PublishRepository.PublicationRow::id)
            .findFirst()
            .orElse(null);
    return new DecisionResult(request.status(), publicationId, publishes.epoch(request.spaceId()));
  }

  private DecisionResult decideLocked(
      AppUser reviewer, UUID requestId, long expectedRevision, String decision, String text) {
    PublishRepository.Request request = lockSpaceAndRequest(requestId);
    long currentRevision = drafts.snapshotRevision(request.spaceId());
    if (currentRevision != expectedRevision) {
      throw ApiException.preconditionFailed(ErrorCode.ETAG_MISMATCH);
    }
    if (!List.of("PENDING_REVIEW", "APPROVED_WAITING_INDEX").contains(request.status())) {
      throw ApiException.cancel("申请已终结，不能再次审核");
    }
    if ("REJECT".equals(decision)) {
      publishes.reject(requestId, reviewer.id(), text);
      jobs.cancelOpenForRequest(requestId);
      audit.record(reviewer.id(), "PUBLISH_REQUEST_REJECTED", "publish_request", requestId,
          java.util.Map.of("spaceId", request.spaceId().toString()));
      return new DecisionResult("REJECTED", null, null);
    }
    if (!hasCurrentBase(request)) {
      throw ApiException.conflict(ErrorCode.PUBLICATION_CHANGED);
    }
    if (!publishes.searchableReady(request.spaceId(), request.snapshotId())) {
      if ("PENDING_REVIEW".equals(request.status())) {
        publishes.markWaitingIndex(requestId, reviewer.id(), text);
        jobs.requireCapacity(JobRepository.Kind.PUBLISH, 1);
        jobs.enqueuePublish(requestId, request.spaceId());
        audit.record(reviewer.id(), "PUBLISH_APPROVED_WAITING_INDEX", "publish_request", requestId,
            java.util.Map.of("spaceId", request.spaceId().toString()));
      }
      return new DecisionResult("APPROVED_WAITING_INDEX", null, request.baseEpoch());
    }
    long baseEpoch = request.baseEpoch();
    UUID publicationId = publishes.publish(request.spaceId(), requestId, reviewer.id(), text, baseEpoch);
    jobs.cancelOpenForRequest(requestId);
    audit.record(reviewer.id(), "SPACE_PUBLISHED", "space", request.spaceId(),
        java.util.Map.of("publicationId", publicationId.toString(), "requestId", requestId.toString()));
    return new DecisionResult("PUBLISHED", publicationId, baseEpoch + 1);
  }

  /** Called by the publish job once indexing completes; revalidates the pointer before switching. */
  @Transactional
  public boolean publishIfStillValid(UUID requestId) {
    PublishRepository.Request request = lockSpaceAndRequest(requestId);
    if (!"APPROVED_WAITING_INDEX".equals(request.status())) {
      return false;
    }
    if (!hasCurrentBase(request)) {
      publishes.cancelRequest(requestId);
      jobs.cancelOpenForRequest(requestId);
      return false;
    }
    if (!publishes.searchableReady(request.spaceId(), request.snapshotId())) {
      return false;
    }
    UUID publicationId =
        publishes.publish(request.spaceId(), requestId, request.reviewedBy(), request.decisionReason(),
            request.baseEpoch());
    audit.record(request.reviewedBy(), "SPACE_PUBLISHED", "space", request.spaceId(),
        java.util.Map.of("publicationId", publicationId.toString(), "requestId", requestId.toString()));
    return true;
  }

  private PublishRepository.Request lockSpaceAndRequest(UUID requestId) {
    UUID spaceId = publishes.findRequest(requestId).orElseThrow(ApiException::notFound).spaceId();
    spaceRows.lockSpace(spaceId);
    publishes.lockRequest(requestId);
    return publishes.findRequest(requestId).orElseThrow(ApiException::notFound);
  }

  private boolean hasCurrentBase(PublishRepository.Request request) {
    return publishes.epoch(request.spaceId()) == request.baseEpoch()
        && java.util.Objects.equals(
            publishes.currentPublication(request.spaceId()).orElse(null), request.basePublicationId());
  }

  @Transactional
  public WithdrawalResult withdraw(
      AppUser actor, UUID spaceId, UUID expectedPublicationId, String reason, String idempotencyKey) {
    if (!actor.isSystemAdmin()) {
      throw ApiException.forbidden();
    }
    spaces.require(spaceId, actor.id());
    if (reason == null || reason.isBlank()) {
      throw ApiException.invalid("撤回必须填写原因");
    }
    String digest = Digests.sha256Hex(spaceId + "|" + expectedPublicationId + "|" + reason);
    var reservation = idempotency.reserve(actor.id(), "WITHDRAW_PUBLICATION", idempotencyKey, digest);
    if (reservation.replay()) {
      return new WithdrawalResult(spaceId, publishes.epoch(spaceId), null);
    }
    try {
      WithdrawalResult result = withdrawLocked(actor, spaceId, expectedPublicationId, reason);
      idempotency.complete(actor.id(), "WITHDRAW_PUBLICATION", idempotencyKey, spaceId, 200);
      return result;
    } catch (RuntimeException ex) {
      idempotency.release(actor.id(), "WITHDRAW_PUBLICATION", idempotencyKey);
      throw ex;
    }
  }

  private WithdrawalResult withdrawLocked(
      AppUser actor, UUID spaceId, UUID expectedPublicationId, String reason) {
    spaceRows.lockSpace(spaceId);
    UUID current = publishes.currentPublication(spaceId).orElse(null);
    if (current == null) {
      throw new ApiException(ErrorCode.STATE_CONFLICT, HttpStatus.CONFLICT, "空间没有已发布版本");
    }
    if (!current.equals(expectedPublicationId)) {
      throw ApiException.conflict(ErrorCode.PUBLICATION_CHANGED);
    }
    publishes.withdraw(spaceId, expectedPublicationId, actor.id(), reason);
    publishes.cancelOpen(spaceId);
    jobs.cancelOpenPublicationsForSpace(spaceId);
    long epoch = publishes.epoch(spaceId);
    audit.record(actor.id(), "SPACE_PUBLICATION_WITHDRAWN", "space", spaceId,
        java.util.Map.of("previousPublicationId", expectedPublicationId.toString(), "reason", reason));
    return new WithdrawalResult(spaceId, epoch, null);
  }

  public record DecisionResult(String status, UUID publicationId, Long epoch) {}

  public record WithdrawalResult(UUID spaceId, long epoch, UUID currentPublicationId) {}
}
