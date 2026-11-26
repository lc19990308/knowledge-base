package dev.aiqa.draft;

import dev.aiqa.common.ApiException;
import dev.aiqa.document.DocumentRepository;
import dev.aiqa.space.SpaceRepository;
import dev.aiqa.space.SpaceService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DraftService {

  private final DraftRepository drafts;
  private final SpaceService spaces;
  private final SpaceRepository spaceRows;
  private final DocumentRepository documents;

  public DraftService(
      DraftRepository drafts, SpaceService spaces, SpaceRepository spaceRows, DocumentRepository documents) {
    this.drafts = drafts;
    this.spaces = spaces;
    this.spaceRows = spaceRows;
    this.documents = documents;
  }

  public DraftRepository.Draft get(UUID userId, UUID spaceId) {
    spaces.requireMaintain(spaceId, userId);
    drafts.ensure(spaceId, userId);
    return drafts.load(spaceId);
  }

  /** Draft revision used as the ETag value; callers must already have checked access. */
  public long revision(UUID spaceId) {
    return drafts.snapshotRevision(spaceId);
  }

  public long revisionFor(UUID userId, UUID spaceId) {
    spaces.requireMaintain(spaceId, userId);
    return drafts.snapshotRevision(spaceId);
  }

  @Transactional
  public DraftRepository.Draft replace(
      UUID userId, UUID spaceId, long expectedRevision, List<DraftRepository.Entry> entries) {
    spaces.requireMaintain(spaceId, userId);
    spaceRows.lockSpace(spaceId);
    drafts.ensure(spaceId, userId);
    validate(spaceId, entries);
    DraftRepository.Draft draft = drafts.replace(spaceId, userId, expectedRevision, entries);
    spaceRows.bumpState(spaceId);
    return draft;
  }

  /**
   * 把流转生成的文档追加进空间草稿：已有该文档版本时保持原样，避免重复条目。
   *
   * <p>在锁空间后读取最新草稿再按原版本写回，调用方不需要持有 ETag。
   */
  @Transactional
  public DraftRepository.Draft appendGeneratedDocument(
      UUID userId, UUID spaceId, UUID documentVersionId, String policyState,
      java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveUntil) {
    spaces.requireMaintain(spaceId, userId);
    if (documents.find(spaceId, documentVersionId).isEmpty()) {
      throw ApiException.notFound();
    }
    spaceRows.lockSpace(spaceId);
    drafts.ensure(spaceId, userId);
    DraftRepository.Draft draft = drafts.load(spaceId);
    List<DraftRepository.Entry> entries = new java.util.ArrayList<>(draft.entries());
    boolean exists =
        entries.stream().anyMatch(entry -> entry.documentVersionId().equals(documentVersionId));
    if (!exists) {
      entries.add(
          new DraftRepository.Entry(
              documentVersionId, effectiveFrom, effectiveUntil, policyState, null, null, null,
              null, null, null, null));
    }
    DraftRepository.Draft next = drafts.replace(spaceId, userId, draft.revision(), entries);
    spaceRows.bumpState(spaceId);
    return next;
  }

  private void validate(UUID spaceId, List<DraftRepository.Entry> entries) {
    if (entries == null) {
      throw ApiException.invalid("entries 不能为空");
    }
    if (entries.size() > 5000) {
      throw ApiException.invalid("单次草稿条目过多");
    }
    Set<UUID> seen = new HashSet<>();
    for (DraftRepository.Entry entry : entries) {
      if (entry.documentVersionId() == null || !seen.add(entry.documentVersionId())) {
        throw ApiException.invalid("同一文档版本不得重复");
      }
      if (documents.find(spaceId, entry.documentVersionId()).isEmpty()) {
        throw ApiException.notFound();
      }
      if (!List.of("EFFECTIVE", "EXPIRED", "UNKNOWN").contains(entry.policyState())) {
        throw ApiException.invalid("policyState 非法");
      }
      if (entry.effectiveFrom() != null && entry.effectiveUntil() != null
          && !entry.effectiveFrom().isBefore(entry.effectiveUntil())) {
        throw ApiException.invalid("生效日期必须早于有效期最后一日");
      }
      if (entry.replacesDocumentVersionId() != null
          && entry.replacesDocumentVersionId().equals(entry.documentVersionId())) {
        throw ApiException.invalid("文档不能替代自身");
      }
    }
    // Replacement links must be inside this space and acyclic.
    for (DraftRepository.Entry entry : entries) {
      UUID replaces = entry.replacesDocumentVersionId();
      int hops = 0;
      while (replaces != null && hops++ < entries.size() + 1) {
        if (documents.find(spaceId, replaces).isEmpty()) {
          throw ApiException.notFound();
        }
        if (replaces.equals(entry.documentVersionId())) {
          throw ApiException.invalid("替代关系不能成环");
        }
        UUID cursor = replaces;
        replaces =
            entries.stream()
                .filter(candidate -> candidate.documentVersionId().equals(cursor))
                .map(DraftRepository.Entry::replacesDocumentVersionId)
                .findFirst()
                .orElse(null);
      }
      if (hops > entries.size()) {
        throw ApiException.invalid("替代关系不能成环");
      }
    }
  }
}
