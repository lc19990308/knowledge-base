package dev.aiqa.cleaning;

import dev.aiqa.auth.AppUser;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.Digests;
import dev.aiqa.common.ErrorCode;
import dev.aiqa.common.FileNames;
import dev.aiqa.document.DocumentRepository;
import dev.aiqa.draft.DraftRepository;
import dev.aiqa.draft.DraftService;
import dev.aiqa.persistence.IdempotencyRepository;
import dev.aiqa.persistence.JobRepository;
import dev.aiqa.publish.AuditRepository;
import dev.aiqa.space.SpaceRepository;
import dev.aiqa.space.SpaceService;
import dev.aiqa.storage.ObjectStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 流转到知识库：把已通过的清洗条目合成一份 Markdown 文档版本，并追加进目标空间草稿。
 *
 * <p>文档后续仍走既有的解析、发布审核、快照与检索链路；这里只负责「生成文档 + 入草稿 + 回写条目」，
 * 发布指针不变，未审核前对普通用户不可见。
 */
@Service
public class CleaningPromotionService {

  private static final Logger log = LoggerFactory.getLogger(CleaningPromotionService.class);
  private static final String PROMOTE_OPERATION = "CLEANING_PROMOTE";
  private static final int MAX_ITEMS = 200;
  private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter NAME_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(BEIJING);
  private static final Set<String> POLICY_STATES =
      Set.of("EFFECTIVE", "EXPIRED", "UNKNOWN");

  private final CleaningRepository repository;
  private final SpaceService spaces;
  private final SpaceRepository spaceRows;
  private final DocumentRepository documents;
  private final DraftService drafts;
  private final JobRepository jobs;
  private final ObjectStore objects;
  private final AuditRepository audit;
  private final IdempotencyRepository idempotency;
  private final TransactionTemplate transactions;

  public CleaningPromotionService(
      CleaningRepository repository,
      SpaceService spaces,
      SpaceRepository spaceRows,
      DocumentRepository documents,
      DraftService drafts,
      JobRepository jobs,
      ObjectStore objects,
      AuditRepository audit,
      IdempotencyRepository idempotency,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.spaces = spaces;
    this.spaceRows = spaceRows;
    this.documents = documents;
    this.drafts = drafts;
    this.jobs = jobs;
    this.objects = objects;
    this.audit = audit;
    this.idempotency = idempotency;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** @param draftRevision 追加后的空间草稿版本，前端据此提示「已进入草稿」 */
  public record Result(
      UUID documentVersionId, String documentName, int versionNumber, long draftRevision,
      int promoted) {}

  /**
   * 执行流转。
   *
   * @param documentName 目标文件名，可为空；空时按批次名与时间生成
   * @param policyState EFFECTIVE / EXPIRED / UNKNOWN，缺省按现行处理
   * @param effectiveFrom 生效日期（含），可为空
   * @param effectiveUntil 失效日期（含），可为空
   */
  @Transactional(propagation = Propagation.NEVER)
  public Result promote(
      AppUser user,
      List<UUID> itemIds,
      UUID spaceId,
      String documentName,
      String policyState,
      String effectiveFrom,
      String effectiveUntil,
      String reason,
      String idempotencyKey) {
    spaces.requireMaintain(spaceId, user.id());
    if (itemIds == null || itemIds.isEmpty()) {
      throw ApiException.invalid("至少选择一条已通过的条目");
    }
    if (itemIds.size() > MAX_ITEMS) {
      throw ApiException.invalid("单次最多流转 " + MAX_ITEMS + " 条");
    }
    String state = policyState == null || policyState.isBlank() ? "EFFECTIVE" : policyState.strip();
    if (!POLICY_STATES.contains(state)) {
      throw ApiException.invalid("政策状态非法");
    }
    LocalDate from = parseDate(effectiveFrom);
    LocalDate until = parseDate(effectiveUntil);
    if (from != null && until != null && !from.isBefore(until)) {
      throw ApiException.invalid("生效日期必须早于失效日期");
    }
    String digest =
        Digests.sha256Hex(
            String.join(
                    ",",
                    itemIds.stream().map(UUID::toString).sorted().toList())
                + "|"
                + spaceId
                + "|"
                + (documentName == null ? "" : documentName.strip())
                + "|"
                + state
                + "|"
                + from
                + "|"
                + until
                + "|"
                + (reason == null ? "" : reason.strip()));
    IdempotencyRepository.Reservation reservation =
        idempotency.reserve(user.id(), PROMOTE_OPERATION, idempotencyKey, digest);
    if (reservation.replay()) {
      return replay(user, reservation.resourceId(), spaceId);
    }
    try {
      List<CleaningRepository.PromotionItem> items = repository.promotionItems(itemIds);
      if (items.size() != itemIds.size()) {
        throw ApiException.notFound();
      }
      requireAllApproved(items);
      String displayName = resolveName(spaceId, documentName, items);
      String normalized = FileNames.normalize(displayName);
      byte[] markdown = render(items).getBytes(StandardCharsets.UTF_8);
      String objectKey = UUID.randomUUID().toString();
      boolean stored = false;
      boolean committed = false;
      try {
        try {
          objects.put(objectKey, new ByteArrayInputStream(markdown), markdown.length);
        } catch (IOException ex) {
          throw new IllegalStateException("流转文档写入对象存储失败", ex);
        }
        stored = true;
        Result result =
            transactions.execute(
                status ->
                    commit(
                        user, itemIds, spaceId, displayName, normalized, objectKey, markdown,
                        Digests.sha256Hex(markdown), state, from, until, reason, idempotencyKey));
        committed = true;
        return result;
      } finally {
        if (!committed) {
          if (stored) {
            discard(objectKey);
          }
          releaseQuietly(user.id(), idempotencyKey);
        }
      }
    } catch (RuntimeException ex) {
      throw ex;
    }
  }

  /** 事务内：二次校验条目、插入文档版本、追加草稿、回写条目并审计。 */
  private Result commit(
      AppUser user,
      List<UUID> itemIds,
      UUID spaceId,
      String displayName,
      String normalized,
      String objectKey,
      byte[] markdown,
      String sha256,
      String policyState,
      LocalDate effectiveFrom,
      LocalDate effectiveUntil,
      String reason,
      String idempotencyKey) {
    spaceRows.lockSpace(spaceId);
    // 锁空间后再读一次条目状态：审核可能刚刚改动过它们。
    requireAllApproved(repository.promotionItems(itemIds));
    UUID documentVersionId = UUID.randomUUID();
    DocumentRepository.DocumentRow row =
        new DocumentRepository.DocumentRow(
            documentVersionId,
            spaceId,
            documentVersionId,
            normalized,
            displayName,
            objectKey,
            sha256,
            "text/markdown",
            markdown.length,
            // 自产正文不经过文件扫描；与导入链路一致用 PENDING，
            // 否则解析 worker 会按「扫描已完成」跳过切块。
            "PENDING",
            "QUEUED",
            0,
            null,
            1,
            null);
    documents.insert(row, user.id());
    jobs.requireCapacity(JobRepository.Kind.PARSE, 1);
    jobs.enqueueParse(documentVersionId, spaceId);
    DraftRepository.Draft draft =
        drafts.appendGeneratedDocument(
            user.id(), spaceId, documentVersionId, policyState, effectiveFrom, effectiveUntil);
    int promoted = repository.markPromoted(itemIds, spaceId, documentVersionId, user.id());
    if (promoted != itemIds.size()) {
      throw ApiException.conflict(ErrorCode.STATE_CONFLICT);
    }
    audit.record(
        user.id(),
        "CLEANING_ITEMS_PROMOTED",
        "space",
        spaceId,
        Map.of(
            "documentVersionId", documentVersionId.toString(),
            "documentName", displayName,
            "promoted", String.valueOf(promoted),
            "reason", reason == null ? "" : reason.strip()));
    idempotency.complete(user.id(), PROMOTE_OPERATION, idempotencyKey, documentVersionId, 201);
    return new Result(documentVersionId, displayName, 1, draft.revision(), promoted);
  }

  /** 只允许流转仍处于「已通过」且未流转过的条目。 */
  private static void requireAllApproved(List<CleaningRepository.PromotionItem> items) {
    for (CleaningRepository.PromotionItem item : items) {
      if (!"APPROVED".equals(item.status()) || item.promotedDocumentVersionId() != null) {
        throw new ApiException(
            ErrorCode.STATE_CONFLICT,
            HttpStatus.CONFLICT,
            "条目状态已变化，请刷新后重试");
      }
    }
  }

  /** 幂等重放：返回上一次生成的文档与当前草稿版本，不重复生成。 */
  private Result replay(AppUser user, UUID documentVersionId, UUID spaceId) {
    DocumentRepository.DocumentRow row =
        documents.find(spaceId, documentVersionId).orElseThrow(ApiException::notFound);
    long draftRevision = drafts.revisionFor(user.id(), spaceId);
    int promoted = Math.toIntExact(repository.promotedTo(documentVersionId));
    return new Result(row.id(), row.displayFilename(), row.versionNumber(), draftRevision, promoted);
  }

  /** 生成 Markdown：文档标题 + 每条一个二级标题，切块后引用可定位到条目。 */
  private static String render(List<CleaningRepository.PromotionItem> items) {
    Set<String> batchNames = new HashSet<>();
    for (CleaningRepository.PromotionItem item : items) {
      batchNames.add(item.batchName());
    }
    StringBuilder markdown = new StringBuilder("# 清洗知识 · ");
    markdown.append(String.join("、", batchNames.stream().sorted().toList())).append("\n\n");
    List<CleaningRepository.PromotionItem> ordered = new ArrayList<>(items);
    ordered.sort(Comparator.comparing(CleaningRepository.PromotionItem::title));
    Set<String> usedTitles = new HashSet<>();
    for (CleaningRepository.PromotionItem item : ordered) {
      String title = cleanTitle(item.title(), usedTitles);
      markdown.append("## ").append(title).append("\n\n")
          .append(item.body().strip()).append("\n\n");
    }
    return markdown.toString().strip() + "\n";
  }

  /** 标题清洗：去换行与井号，重名时追加序号，避免文档出现两个同名小节。 */
  private static String cleanTitle(String title, Set<String> used) {
    String base =
        title.replaceAll("[\\r\\n]+", " ").replaceFirst("^#+\\s*", "").strip();
    if (base.isEmpty()) {
      base = "未命名条目";
    }
    if (base.length() > 120) {
      base = base.substring(0, 120);
    }
    String candidate = base;
    int suffix = 2;
    while (!used.add(candidate)) {
      candidate = base + "（" + suffix++ + "）";
    }
    return candidate;
  }

  /** 文件名：用户指定 → 默认「清洗知识-批次-时间」，并避开同空间重名。 */
  private String resolveName(
      UUID spaceId, String requested, List<CleaningRepository.PromotionItem> items) {
    String base =
        requested == null || requested.isBlank() ? defaultName(items) : requested.strip();
    if (base.length() > 180) {
      base = base.substring(0, 180);
    }
    if (!base.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
      base = base + ".md";
    }
    String candidate = base;
    int suffix = 2;
    while (documents.findByNormalizedName(spaceId, FileNames.normalize(candidate)).isPresent()) {
      if (suffix > 50) {
        throw ApiException.invalid("同名文档过多，请换一个文档名");
      }
      candidate = base.substring(0, base.length() - 3) + "-" + suffix + ".md";
      suffix++;
    }
    return candidate;
  }

  private static String defaultName(List<CleaningRepository.PromotionItem> items) {
    String batch = items.isEmpty() ? "清洗知识" : items.get(0).batchName();
    String cleaned = batch.replaceAll("[/\\\\:*?\"<>|\\s]+", "-");
    if (cleaned.length() > 40) {
      cleaned = cleaned.substring(0, 40);
    }
    return "清洗知识-" + cleaned + "-" + NAME_STAMP.format(java.time.Instant.now());
  }

  private static LocalDate parseDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value.strip());
    } catch (RuntimeException ex) {
      throw ApiException.invalid("日期格式必须为 YYYY-MM-DD");
    }
  }

  private void releaseQuietly(UUID userId, String idempotencyKey) {
    try {
      idempotency.release(userId, PROMOTE_OPERATION, idempotencyKey);
    } catch (RuntimeException ex) {
      log.warn("流转幂等键释放失败 key={}", idempotencyKey, ex);
    }
  }

  private void discard(String objectKey) {
    try {
      objects.delete(objectKey);
    } catch (IOException | RuntimeException ex) {
      log.warn("流转文档清理失败 object={}", objectKey, ex);
    }
  }
}
