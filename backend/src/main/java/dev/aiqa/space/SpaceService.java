package dev.aiqa.space;

import dev.aiqa.auth.AppUser;
import dev.aiqa.common.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpaceService {

  private final SpaceRepository spaces;
  private final dev.aiqa.publish.AuditRepository audit;
  private final dev.aiqa.auth.UserRepository users;

  public SpaceService(SpaceRepository spaces, dev.aiqa.publish.AuditRepository audit,
      dev.aiqa.auth.UserRepository users) {
    this.spaces = spaces;
    this.audit = audit;
    this.users = users;
  }

  @Transactional
  public SpaceRepository.SpaceRow create(AppUser user, String name, String department) {
    String trimmed = name == null ? "" : name.strip();
    if (trimmed.isEmpty() || trimmed.length() > 120) {
      throw ApiException.invalid("空间名称必须为 1–120 个字符");
    }
    if (!user.isSystemAdmin() && !user.canCreateSpace()) {
      throw ApiException.forbidden();
    }
    String team = department == null ? null : department.strip();
    UUID id = spaces.create(user.id(), trimmed, team == null || team.isEmpty() ? null : team);
    return require(id, user.id());
  }

  public SpaceRepository.SpaceRow require(UUID spaceId, UUID userId) {
    return spaces.findForUser(spaceId, userId).orElseThrow(ApiException::notFound);
  }

  /** Any membership (reader or maintainer) or system administrator; otherwise a uniform 404. */
  public SpaceRepository.SpaceRow requireVisible(dev.aiqa.auth.AppUser user, UUID spaceId) {
    SpaceRepository.SpaceRow space = require(spaceId, user.id());
    boolean member = space.canRead() || space.canMaintain();
    if (!member && !user.isSystemAdmin()) {
      throw ApiException.notFound();
    }
    return space;
  }

  /** Reader-visible space: membership plus a current publication. */
  public SpaceRepository.SpaceRow requireReadable(UUID spaceId, UUID userId) {
    SpaceRepository.SpaceRow space = require(spaceId, userId);
    if (!space.isReadable()) {
      throw ApiException.notFound();
    }
    return space;
  }

  /**
   * 检索入口：普通用户仍是「成员 + 当前已发布」；系统管理员只校验空间存在，
   * 未发布与已撤回的空间也可检索，具体内容来源由 SearchService 解析。
   */
  public SpaceRepository.SpaceRow requireSearchable(AppUser user, UUID spaceId) {
    SpaceRepository.SpaceRow space = require(spaceId, user.id());
    if (user.isSystemAdmin()) {
      return space;
    }
    if (!space.isReadable()) {
      throw ApiException.notFound();
    }
    return space;
  }

  public SpaceRepository.SpaceRow requireMaintain(UUID spaceId, UUID userId) {
    SpaceRepository.SpaceRow space = require(spaceId, userId);
    if (!space.canMaintain()) {
      throw ApiException.forbidden();
    }
    return space;
  }

  public List<SpaceRepository.SpaceRow> list(AppUser user) {
    return spaces.listForUser(user.id());
  }

  /** 成员在各空间的授权，供系统设置一次查看并分配多个空间。 */
  public List<SpaceRepository.UserSpaceRow> userMemberships(AppUser actor, UUID userId) {
    if (!actor.isSystemAdmin()) {
      throw ApiException.forbidden();
    }
    users.findById(userId).orElseThrow(ApiException::notFound);
    return spaces.membershipsOfUser(userId);
  }

  public record MembershipGrant(boolean canRead, boolean canMaintain) {}

  /**
   * 全量覆盖成员的空间授权：{@code desired} 中未列出的空间撤销授权，两个权限都为 false 的条目
   * 等价于撤销。成员自己创建的空间始终保留读取+维护，避免空间所有者失去自己的空间。
   */
  @Transactional
  public List<SpaceRepository.UserSpaceRow> replaceUserMemberships(
      AppUser actor, UUID userId, Map<UUID, MembershipGrant> desired) {
    if (!actor.isSystemAdmin()) {
      throw ApiException.forbidden();
    }
    AppUser target = users.findById(userId).orElseThrow(ApiException::notFound);
    java.util.Set<UUID> owned = new java.util.HashSet<>(spaces.ownedSpaceIds(userId));
    java.util.List<UUID> involved = new java.util.ArrayList<>(desired.keySet());
    involved.addAll(owned);
    involved.sort(java.util.Comparator.naturalOrder());
    for (UUID spaceId : involved) {
      spaces.findForUser(spaceId, actor.id()).orElseThrow(ApiException::notFound);
    }

    Map<UUID, SpaceRepository.UserSpaceRow> before = bySpace(spaces.membershipsOfUser(userId));
    for (UUID spaceId : involved) {
      spaces.lockSpace(spaceId);
    }
    spaces.clearMembershipsExceptOwned(userId);
    for (UUID spaceId : owned) {
      spaces.setMembership(spaceId, userId, true, true, actor.id());
    }
    for (Map.Entry<UUID, MembershipGrant> entry : desired.entrySet()) {
      if (owned.contains(entry.getKey())) {
        continue;
      }
      MembershipGrant grant = entry.getValue();
      if (!grant.canRead() && !grant.canMaintain()) {
        continue;
      }
      spaces.setMembership(entry.getKey(), userId, grant.canRead(), grant.canMaintain(),
          actor.id());
    }

    List<SpaceRepository.UserSpaceRow> after = spaces.membershipsOfUser(userId);
    Map<UUID, SpaceRepository.UserSpaceRow> afterBySpace = bySpace(after);
    java.util.Set<UUID> touched = new java.util.TreeSet<>(before.keySet());
    touched.addAll(afterBySpace.keySet());
    for (UUID spaceId : touched) {
      SpaceRepository.UserSpaceRow old = before.get(spaceId);
      SpaceRepository.UserSpaceRow now = afterBySpace.get(spaceId);
      if (old != null && now != null && old.canRead() == now.canRead()
          && old.canMaintain() == now.canMaintain()) {
        continue;
      }
      spaces.bumpState(spaceId);
      audit.record(actor.id(), "SPACE_MEMBER_UPDATED", "space", spaceId,
          Map.of("userId", target.id().toString(),
              "canRead", Boolean.toString(now != null && now.canRead()),
              "canMaintain", Boolean.toString(now != null && now.canMaintain())));
    }
    return after;
  }

  private static Map<UUID, SpaceRepository.UserSpaceRow> bySpace(
      List<SpaceRepository.UserSpaceRow> rows) {
    Map<UUID, SpaceRepository.UserSpaceRow> map = new java.util.LinkedHashMap<>();
    for (SpaceRepository.UserSpaceRow row : rows) {
      map.put(row.spaceId(), row);
    }
    return map;
  }

  @Transactional
  public SpaceRepository.MemberRow setMember(
      AppUser actor, UUID spaceId, UUID userId, boolean canRead, boolean canMaintain) {
    if (!actor.isSystemAdmin()) {
      throw ApiException.forbidden();
    }
    requireVisible(actor, spaceId);
    AppUser target = users.findById(userId).orElseThrow(ApiException::notFound);
    spaces.lockSpace(spaceId);
    spaces.setMembership(spaceId, userId, canRead, canMaintain, actor.id());
    spaces.bumpState(spaceId);
    audit.record(actor.id(), "SPACE_MEMBER_UPDATED", "space", spaceId,
        java.util.Map.of("userId", userId.toString(),
            "canRead", Boolean.toString(canRead),
            "canMaintain", Boolean.toString(canMaintain)));
    return new SpaceRepository.MemberRow(userId, target.displayName(), canRead, canMaintain);
  }

  public SpaceRepository.DocumentStats stats(java.util.UUID spaceId, boolean published) {
    return spaces.documentStats(spaceId, published);
  }

  public List<SpaceRepository.MemberRow> members(AppUser actor, UUID spaceId) {
    if (!actor.isSystemAdmin()) {
      throw ApiException.forbidden();
    }
    requireVisible(actor, spaceId);
    return spaces.members(spaceId);
  }
}
