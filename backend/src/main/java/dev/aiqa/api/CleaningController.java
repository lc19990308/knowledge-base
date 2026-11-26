package dev.aiqa.api;

import dev.aiqa.auth.AppUser;
import dev.aiqa.auth.CurrentUser;
import dev.aiqa.cleaning.CleaningPromotionService;
import dev.aiqa.cleaning.CleaningRepository;
import dev.aiqa.cleaning.CleaningReviewService;
import dev.aiqa.cleaning.CleaningService;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import dev.aiqa.common.PageQuery;
import dev.aiqa.common.PageView;
import dev.aiqa.common.RateLimiter;
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
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 数据清洗接口：上传原始数据、查看批次、清洗草稿箱列表、审核与流转。
 *
 * <p>本期仅系统管理员可访问；上传与流转在权限之外还要求目标空间可维护（管理员天然满足）。
 * 所有写操作都要求 Idempotency-Key，并通过统一的限流与错误信封返回。
 */
@RestController
@RequestMapping("/api/v1/cleaning")
public class CleaningController {

  private final CurrentUser current;
  private final CleaningService cleaning;
  private final CleaningRepository repository;
  private final CleaningReviewService reviews;
  private final CleaningPromotionService promotions;
  private final RateLimiter limiter;
  private final ObjectMapper mapper = new ObjectMapper();

  public CleaningController(
      CurrentUser current,
      CleaningService cleaning,
      CleaningRepository repository,
      CleaningReviewService reviews,
      CleaningPromotionService promotions,
      RateLimiter limiter) {
    this.current = current;
    this.cleaning = cleaning;
    this.repository = repository;
    this.reviews = reviews;
    this.promotions = promotions;
    this.limiter = limiter;
  }

  // ---------- 视图 ----------

  public record BatchView(
      UUID batchId, String name, UUID targetSpaceId, String status, boolean modelScoring,
      int fileCount, int skippedCount, int failedFileCount, int itemCount, int duplicateCount,
      Integer averageScore, String errorCode, String createdAt, String finishedAt) {}

  public record SourceFileView(
      UUID sourceFileId, String relativePath, String displayName, long sizeBytes, String state,
      int recordCount, String errorCode) {}

  public record BatchDetailView(
      UUID batchId, String name, UUID targetSpaceId, String status, boolean modelScoring,
      int fileCount, int skippedCount, int failedFileCount, int itemCount, int duplicateCount,
      Integer averageScore, String errorCode, String createdAt, String finishedAt,
      List<SourceFileView> sourceFiles) {}

  public record ScoreDetailView(
      int length, int completeness, int noise, int information, int structure) {}

  public record ItemView(
      UUID itemId, UUID batchId, String batchName, String title, String excerpt, String sourcePath,
      int ruleScore, Integer modelScore, int totalScore, String scoreState, String modelReason,
      String status, UUID targetSpaceId, String targetSpaceName, UUID promotedDocumentVersionId,
      String createdAt, String reviewedAt, String reviewReason) {}

  public record ItemDetailView(
      UUID itemId, UUID batchId, String batchName, String title, String excerpt, String sourcePath,
      int ruleScore, Integer modelScore, int totalScore, String scoreState, String modelReason,
      String status, UUID targetSpaceId, String targetSpaceName, UUID promotedDocumentVersionId,
      String createdAt, String reviewedAt, String reviewReason, String body, String rawExcerpt,
      ScoreDetailView scoreDetail, String reviewedBy, UUID promotedSpaceId, String promotedAt) {}

  public record ReviewInput(List<String> itemIds, String decision, String reason) {}

  public record PromotionInput(
      List<String> itemIds, String spaceId, String documentName, String policyState,
      String effectiveFrom, String effectiveUntil, String reason) {}

  private static BatchView view(CleaningRepository.BatchRow row) {
    return new BatchView(
        row.id(), row.name(), row.targetSpaceId(), row.status(), row.modelScoring(),
        row.fileCount(), row.skippedCount(), row.failedFileCount(), row.itemCount(),
        row.duplicateCount(), row.averageScore(), row.errorCode(),
        row.createdAt() == null ? null : row.createdAt().toString(),
        row.finishedAt() == null ? null : row.finishedAt().toString());
  }

  private static ItemView itemView(CleaningRepository.ItemRow row) {
    return new ItemView(
        row.id(), row.batchId(), row.batchName(), row.title(), row.excerpt(), row.sourcePath(),
        row.ruleScore(), row.modelScore(), row.totalScore(), row.scoreState(), row.modelReason(),
        row.status(), row.targetSpaceId(), row.targetSpaceName(), row.promotedDocumentVersionId(),
        row.createdAt() == null ? null : row.createdAt().toString(),
        row.reviewedAt() == null ? null : row.reviewedAt().toString(),
        row.reviewReason());
  }

  private ItemDetailView detailView(CleaningRepository.ItemDetailRow row) {
    return new ItemDetailView(
        row.id(), row.batchId(), row.batchName(), row.title(), row.excerpt(), row.sourcePath(),
        row.ruleScore(), row.modelScore(), row.totalScore(), row.scoreState(), row.modelReason(),
        row.status(), row.targetSpaceId(), row.targetSpaceName(), row.promotedDocumentVersionId(),
        row.createdAt() == null ? null : row.createdAt().toString(),
        row.reviewedAt() == null ? null : row.reviewedAt().toString(),
        row.reviewReason(), row.body(), row.rawExcerpt(), scoreDetail(row.scoreDetailJson()),
        row.reviewedBy(), row.promotedSpaceId(),
        row.promotedAt() == null ? null : row.promotedAt().toString());
  }

  /** 评分明细是 JSONB；缺字段按 0 返回，前端展示不因脏数据报错。 */
  private ScoreDetailView scoreDetail(String json) {
    if (json == null || json.isBlank()) {
      return new ScoreDetailView(0, 0, 0, 0, 0);
    }
    try {
      JsonNode node = mapper.readTree(json);
      return new ScoreDetailView(
          node.path("length").asInt(0),
          node.path("completeness").asInt(0),
          node.path("noise").asInt(0),
          node.path("information").asInt(0),
          node.path("structure").asInt(0));
    } catch (RuntimeException ex) {
      return new ScoreDetailView(0, 0, 0, 0, 0);
    }
  }

  // ---------- 批次 ----------

  /** 上传原始数据集：文件夹文件（带相对路径）与压缩包可以同时提交。 */
  @PostMapping("/batches")
  public ResponseEntity<BatchView> upload(
      @RequestParam(name = "files", required = false) List<MultipartFile> files,
      @RequestParam(name = "paths", required = false) List<String> paths,
      @RequestParam(name = "archive", required = false) MultipartFile archive,
      @RequestParam(name = "name", required = false) String name,
      @RequestParam(name = "targetSpaceId", required = false) String targetSpaceId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    AppUser user = current.requireAdmin();
    requireIdempotency(idempotencyKey);
    limiter.checkWrites(user.id());
    UUID space =
        targetSpaceId == null || targetSpaceId.isBlank()
            ? null
            : Http.pathUuid(targetSpaceId, "targetSpaceId");
    CleaningRepository.BatchRow row =
        cleaning.upload(user, space, name, files, paths, archive, idempotencyKey);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header(HttpHeaders.LOCATION, "/api/v1/cleaning/batches/" + row.id())
        .body(view(row));
  }

  @GetMapping("/batches")
  public PageView<BatchView> batches(
      @RequestParam(name = "status", required = false, defaultValue = "") String status,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {
    current.requireAdmin();
    PageQuery page = PageQuery.of(limit, offset);
    String state = status == null ? "" : status.strip().toUpperCase(java.util.Locale.ROOT);
    List<CleaningRepository.BatchRow> rows =
        repository.listBatches(state, page.limit(), page.offset());
    return PageView.of(rows.stream().map(CleaningController::view).toList(), repository.countBatches(state));
  }

  @GetMapping("/batches/{batchId}")
  public BatchDetailView batch(@PathVariable String batchId) {
    current.requireAdmin();
    UUID id = Http.pathUuid(batchId, "batchId");
    CleaningRepository.BatchRow row = repository.findBatch(id).orElseThrow(ApiException::notFound);
    List<SourceFileView> files =
        repository.sourceFiles(id).stream()
            .map(
                file ->
                    new SourceFileView(
                        file.id(), file.relativePath(), file.displayName(), file.sizeBytes(),
                        file.state(), file.recordCount(), file.errorCode()))
            .toList();
    return new BatchDetailView(
        row.id(), row.name(), row.targetSpaceId(), row.status(), row.modelScoring(),
        row.fileCount(), row.skippedCount(), row.failedFileCount(), row.itemCount(),
        row.duplicateCount(), row.averageScore(), row.errorCode(),
        row.createdAt() == null ? null : row.createdAt().toString(),
        row.finishedAt() == null ? null : row.finishedAt().toString(), files);
  }

  /** 对仅规则分的条目重新排队打分。 */
  @PostMapping("/batches/{batchId}/rescore")
  public Map<String, Object> rescore(@PathVariable String batchId) {
    AppUser user = current.requireAdmin();
    limiter.checkWrites(user.id());
    UUID id = Http.pathUuid(batchId, "batchId");
    int enqueued = reviews.rescore(user, id);
    return Map.of("enqueued", enqueued);
  }

  // ---------- 草稿箱 ----------

  @GetMapping("/items")
  public PageView<ItemView> items(
      @RequestParam(name = "status", required = false, defaultValue = "") String status,
      @RequestParam(name = "batchId", required = false) String batchId,
      @RequestParam(name = "targetSpaceId", required = false) String targetSpaceId,
      @RequestParam(name = "minScore", required = false) Integer minScore,
      @RequestParam(name = "q", required = false, defaultValue = "") String query,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {
    current.requireAdmin();
    PageQuery page = PageQuery.of(limit, offset);
    String state = status == null ? "" : status.strip().toUpperCase(java.util.Locale.ROOT);
    UUID batch = batchId == null || batchId.isBlank() ? null : Http.pathUuid(batchId, "batchId");
    UUID space =
        targetSpaceId == null || targetSpaceId.isBlank()
            ? null
            : Http.pathUuid(targetSpaceId, "targetSpaceId");
    String text = query == null ? "" : query.strip();
    List<CleaningRepository.ItemRow> rows =
        repository.listItems(state, batch, space, minScore, text, page.limit(), page.offset());
    long total = repository.countItems(state, batch, space, minScore, text);
    return PageView.of(rows.stream().map(CleaningController::itemView).toList(), total);
  }

  @GetMapping("/items/{itemId}")
  public ItemDetailView item(@PathVariable String itemId) {
    current.requireAdmin();
    UUID id = Http.pathUuid(itemId, "itemId");
    CleaningRepository.ItemDetailRow row =
        repository.findItem(id).orElseThrow(ApiException::notFound);
    return detailView(row);
  }

  /** 批量审核：通过为轻确认，驳回必须带原因。 */
  @PostMapping("/items/review")
  public CleaningReviewService.ReviewResult review(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody ReviewInput input) {
    AppUser user = current.requireAdmin();
    requireIdempotency(idempotencyKey);
    limiter.checkWrites(user.id());
    if (input == null) {
      throw ApiException.invalid("缺少审核内容");
    }
    return reviews.review(
        user, uuids(input.itemIds(), "itemIds"), input.decision(), input.reason(), idempotencyKey);
  }

  /** 流转到知识库：生成 Markdown 文档版本并加入目标空间草稿。 */
  @PostMapping("/promotions")
  public ResponseEntity<CleaningPromotionService.Result> promote(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody PromotionInput input) {
    AppUser user = current.requireAdmin();
    requireIdempotency(idempotencyKey);
    limiter.checkWrites(user.id());
    if (input == null) {
      throw ApiException.invalid("缺少流转内容");
    }
    if (input.spaceId() == null || input.spaceId().isBlank()) {
      throw ApiException.invalid("spaceId 不能为空");
    }
    UUID spaceId = Http.pathUuid(input.spaceId(), "spaceId");
    CleaningPromotionService.Result result =
        promotions.promote(
            user,
            uuids(input.itemIds(), "itemIds"),
            spaceId,
            input.documentName(),
            input.policyState(),
            input.effectiveFrom(),
            input.effectiveUntil(),
            input.reason(),
            idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.LOCATION, "/api/v1/spaces/" + spaceId + "/draft")
        .body(result);
  }

  /** 把字符串 ID 列表转成 UUID；缺项或非法值按 400 处理。 */
  private static List<UUID> uuids(List<String> values, String field) {
    if (values == null || values.isEmpty()) {
      throw ApiException.invalid(field + " 不能为空");
    }
    List<UUID> parsed = new java.util.ArrayList<>(values.size());
    for (String value : values) {
      if (value == null || value.isBlank()) {
        throw ApiException.invalid(field + " 含空值");
      }
      parsed.add(Http.pathUuid(value, field));
    }
    return List.copyOf(parsed);
  }

  private static void requireIdempotency(String key) {
    if (key == null || key.isBlank()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
    }
  }
}
