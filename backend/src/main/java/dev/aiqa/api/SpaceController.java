package dev.aiqa.api;

import dev.aiqa.auth.AppUser;
import dev.aiqa.auth.CurrentUser;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import dev.aiqa.publish.PublishRepository;
import dev.aiqa.publish.PublishService;
import dev.aiqa.space.SpaceRepository;
import dev.aiqa.space.SpaceService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SpaceController {

  private final CurrentUser current;
  private final SpaceService spaces;
  private final PublishService publishes;
  private final dev.aiqa.common.RateLimiter limiter;
  private final dev.aiqa.document.DocumentRepository documents;
  private final dev.aiqa.persistence.JobRepository jobs;

  public SpaceController(CurrentUser current, SpaceService spaces, PublishService publishes,
      dev.aiqa.common.RateLimiter limiter,
      dev.aiqa.document.DocumentRepository documents,
      dev.aiqa.persistence.JobRepository jobs) {
    this.current = current;
    this.spaces = spaces;
    this.publishes = publishes;
    this.limiter = limiter;
    this.documents = documents;
    this.jobs = jobs;
  }

  public record SpaceInput(String name, String department) {}

  /** 契约把 currentPublicationId 列为必填（可空）；未发布空间也要输出 null。 */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record SpaceView(
      UUID spaceId, String name, long epoch, UUID currentPublicationId, boolean canRead,
      boolean canMaintain, long documentCount, long searchableCount, String updatedAt,
      String department) {}

  public record MemberInput(Boolean canRead, Boolean canMaintain) {}

  public record MemberView(UUID userId, String displayName, boolean canRead, boolean canMaintain) {}

  public record WithdrawInput(String expectedPublicationId, String reason) {}

  public record WithdrawResult(UUID spaceId, long epoch, UUID currentPublicationId) {}

  private SpaceView view(SpaceRepository.SpaceRow row) {
    SpaceRepository.DocumentStats stats =
        spaces.stats(row.id(), row.currentPublicationId() != null);
    return new SpaceView(
        row.id(), row.name(), row.publicationEpoch(), row.currentPublicationId(), row.canRead(),
        row.canMaintain(), stats.documents(), stats.searchable(),
        row.updatedAt() == null ? null : row.updatedAt().toString(), row.department());
  }

  /**
   * 空间列表支持名称筛选和发布状态筛选，并把 offset/limit 之前的总数放在 total 里。
   * 状态取值：PUBLISHED（当前有发布内容）、DRAFT（没有任何发布）、REVIEWING（存在待审核或待生效的申请）。
   */
  @GetMapping("/spaces")
  public dev.aiqa.common.PageView<SpaceView> list(
      @RequestParam(name = "q", required = false, defaultValue = "") String query,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {
    AppUser user = current.require();
    dev.aiqa.common.PageQuery page = dev.aiqa.common.PageQuery.of(limit, offset);
    String name = query == null ? "" : query.strip();
    String state = status == null ? "" : status.strip().toUpperCase(java.util.Locale.ROOT);
    if (!state.isEmpty() && !SPACE_STATUSES.contains(state)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
          "status 只支持 PUBLISHED、DRAFT、REVIEWING");
    }
    List<SpaceRepository.SpaceRow> rows = spaces.list(user);
    if (!state.isEmpty()) {
      Set<UUID> reviewing = state.equals("REVIEWING") ? reviewingSpaceIds(user) : Set.of();
      rows = rows.stream()
          .filter(row -> switch (state) {
            case "PUBLISHED" -> row.currentPublicationId() != null;
            case "DRAFT" -> row.currentPublicationId() == null;
            default -> reviewing.contains(row.id());
          })
          .toList();
    }
    if (!name.isEmpty()) {
      rows = rows.stream()
          .filter(row -> row.name() != null && row.name().contains(name))
          .toList();
    }
    List<SpaceView> items = page.slice(rows).stream().map(this::view).toList();
    return dev.aiqa.common.PageView.of(items, rows.size());
  }

  private static final Set<String> SPACE_STATUSES = Set.of("PUBLISHED", "DRAFT", "REVIEWING");

  /** 有未结束发布申请的空间；与知识库页面的「审核中」角标使用同一可见范围。 */
  private Set<UUID> reviewingSpaceIds(AppUser user) {
    Set<UUID> ids = new java.util.HashSet<>();
    for (PublishRepository.Request request : publishes.listVisible(user)) {
      if ("PENDING_REVIEW".equals(request.status())
          || "APPROVED_WAITING_INDEX".equals(request.status())) {
        ids.add(request.spaceId());
      }
    }
    return ids;
  }

  @PostMapping("/spaces")
  public ResponseEntity<SpaceView> create(@RequestBody SpaceInput input) {
    AppUser user = current.require();
    SpaceRepository.SpaceRow row =
        spaces.create(user, input == null ? null : input.name(),
            input == null ? null : input.department());
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.LOCATION, "/api/v1/spaces/" + row.id())
        .body(view(row));
  }

  @GetMapping("/spaces/{spaceId}")
  public SpaceView get(@PathVariable String spaceId) {
    AppUser user = current.require();
    return view(spaces.requireVisible(user, Http.pathUuid(spaceId, "spaceId")));
  }

  @PutMapping("/spaces/{spaceId}/members/{userId}")
  public MemberView setMember(
      @PathVariable String spaceId, @PathVariable String userId, @RequestBody MemberInput input) {
    AppUser actor = current.requireAdmin();
    UUID space = Http.pathUuid(spaceId, "spaceId");
    UUID member = Http.pathUuid(userId, "userId");
    if (input.canRead() == null || input.canMaintain() == null) {
      throw ApiException.invalid("请同时指定读取权限和维护权限");
    }
    var result = spaces.setMember(actor, space, member, input.canRead(), input.canMaintain());
    return new MemberView(result.userId(), result.displayName(), result.canRead(), result.canMaintain());
  }

  @GetMapping("/spaces/{spaceId}/members")
  public dev.aiqa.common.PageView<MemberView> members(@PathVariable String spaceId) {
    AppUser actor = current.requireAdmin();
    UUID space = Http.pathUuid(spaceId, "spaceId");
    List<MemberView> items =
        spaces.members(actor, space).stream()
            .map(row -> new MemberView(row.userId(), row.displayName(), row.canRead(), row.canMaintain()))
            .toList();
    return dev.aiqa.common.PageView.of(items);
  }

  public record MembershipEntry(String spaceId, Boolean canRead, Boolean canMaintain) {}

  public record SpaceMembershipsInput(List<MembershipEntry> memberships) {}

  public record UserSpaceView(
      UUID spaceId, String spaceName, boolean canRead, boolean canMaintain, boolean owned) {}

  private UserSpaceView userSpaceView(SpaceRepository.UserSpaceRow row) {
    return new UserSpaceView(
        row.spaceId(), row.spaceName(), row.canRead(), row.canMaintain(), row.owned());
  }

  /** 系统管理员查看某成员在各空间的授权，用于一次分配多个空间。 */
  @GetMapping("/users/{userId}/space-memberships")
  public dev.aiqa.common.PageView<UserSpaceView> userSpaceMemberships(
      @PathVariable String userId) {
    AppUser actor = current.requireAdmin();
    UUID user = Http.pathUuid(userId, "userId");
    List<UserSpaceView> items =
        spaces.userMemberships(actor, user).stream().map(this::userSpaceView).toList();
    return dev.aiqa.common.PageView.of(items);
  }

  /**
   * 系统管理员一次提交成员的全部空间授权：未列出的空间撤销授权，成员自己创建的空间始终保留读取
   * 与维护权限。整个提交在同一事务内生效，避免多空间分配出现部分成功。
   */
  @PutMapping("/users/{userId}/space-memberships")
  public dev.aiqa.common.PageView<UserSpaceView> replaceUserSpaceMemberships(
      @PathVariable String userId, @RequestBody SpaceMembershipsInput input) {
    AppUser actor = current.requireAdmin();
    UUID user = Http.pathUuid(userId, "userId");
    if (input == null || input.memberships() == null) {
      throw ApiException.invalid("请提交空间授权列表");
    }
    Map<UUID, SpaceService.MembershipGrant> grants = new java.util.LinkedHashMap<>();
    for (MembershipEntry entry : input.memberships()) {
      if (entry == null || entry.spaceId() == null || entry.canRead() == null
          || entry.canMaintain() == null) {
        throw ApiException.invalid("每个空间都要指定空间 ID、读取权限和维护权限");
      }
      UUID space = Http.pathUuid(entry.spaceId(), "spaceId");
      if (grants.put(space, new SpaceService.MembershipGrant(entry.canRead(), entry.canMaintain()))
          != null) {
        throw ApiException.invalid("同一个空间不能重复授权");
      }
    }
    List<UserSpaceView> items =
        spaces.replaceUserMemberships(actor, user, grants).stream().map(this::userSpaceView).toList();
    return dev.aiqa.common.PageView.of(items);
  }

  @PostMapping("/spaces/{spaceId}/reindex")
  @org.springframework.transaction.annotation.Transactional
  public Map<String, Object> reindex(
      @PathVariable String spaceId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    AppUser actor = current.require();
    requireIdempotency(idempotencyKey);
    limiter.checkWrites(actor.id());
    UUID space = Http.pathUuid(spaceId, "spaceId");
    spaces.requireMaintain(space, actor.id());
    jobs.requireCapacity(dev.aiqa.persistence.JobRepository.Kind.INDEX, 0);
    int enqueued = 0;
    for (var document : documents.listBySpace(space)) {
      if ("READY".equals(document.parseState())) {
        if (jobs.enqueueIndex(document.id(), space)) {
          enqueued++;
        }
      }
    }
    return Map.of("spaceId", space, "enqueued", enqueued);
  }

  @PostMapping("/spaces/{spaceId}/withdrawals")
  public WithdrawResult withdraw(
      @PathVariable String spaceId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody WithdrawInput input) {
    AppUser actor = current.requireAdmin();
    requireIdempotency(idempotencyKey);
    limiter.checkWrites(actor.id());
    UUID space = Http.pathUuid(spaceId, "spaceId");
    UUID expected = Http.pathUuid(input.expectedPublicationId(), "expectedPublicationId");
    PublishService.WithdrawalResult result =
        publishes.withdraw(actor, space, expected, input.reason(), idempotencyKey);
    return new WithdrawResult(result.spaceId(), result.epoch(), result.currentPublicationId());
  }

  private static void requireIdempotency(String key) {
    if (key == null || key.isBlank()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
    }
  }
}
