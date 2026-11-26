package dev.aiqa.api;

import dev.aiqa.auth.AppUser;
import dev.aiqa.auth.CurrentUser;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import dev.aiqa.common.RateLimiter;
import dev.aiqa.document.DocumentRepository;
import dev.aiqa.document.ImportRepository;
import dev.aiqa.document.ImportService;
import dev.aiqa.space.SpaceService;
import dev.aiqa.storage.ObjectStore;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class DocumentController {

  private final CurrentUser current;
  private final ImportService imports;
  private final SpaceService spaces;
  private final DocumentRepository documents;
  private final ObjectStore objects;
  private final dev.aiqa.common.RateLimiter limiter;

  public DocumentController(
      CurrentUser current, ImportService imports, SpaceService spaces, DocumentRepository documents,
      ObjectStore objects, dev.aiqa.common.RateLimiter limiter) {
    this.current = current;
    this.imports = imports;
    this.spaces = spaces;
    this.documents = documents;
    this.objects = objects;
    this.limiter = limiter;
  }

  public record ImportFileView(
      String name, String status, UUID documentVersionId, String code,
      Long sizeBytes, String mediaType, Integer versionNumber) {}

  public record ImportBatchView(
      UUID batchId, UUID spaceId, String status, List<ImportFileView> files) {}

  @PostMapping("/spaces/{spaceId}/imports")
  public ResponseEntity<ImportBatchView> importFiles(
      @PathVariable String spaceId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestParam("files") List<MultipartFile> files) {
    AppUser user = current.require();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
    }
    limiter.checkWrites(user.id());
    UUID space = Http.pathUuid(spaceId, "spaceId");
    ImportService.Accepted accepted = imports.importFiles(user.id(), space, files, idempotencyKey);
    ImportRepository.Batch batch = imports.findBatch(user.id(), accepted.batchId());
    ImportBatchView view = view(batch);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header(HttpHeaders.LOCATION, "/api/v1/imports/" + batch.id())
        .body(view);
  }

  private static ImportBatchView view(ImportRepository.Batch batch) {
    return new ImportBatchView(
        batch.id(), batch.spaceId(), batch.status(),
        batch.items().stream()
            .map(item -> new ImportFileView(
                item.submittedFilename(), item.status(), item.documentVersionId(), item.errorCode(),
                item.sizeBytes(), item.mediaType(), item.versionNumber()))
            .toList());
  }

  @GetMapping("/imports/{batchId}")
  public ImportBatchView getBatch(@PathVariable String batchId) {
    AppUser user = current.require();
    UUID id = Http.pathUuid(batchId, "batchId");
    return limiter.read(RateLimiter.Read.STATUS, user.id(), () -> view(imports.findBatch(user.id(), id)));
  }

  public record UpdateView(UUID documentVersionId, Integer versionNumber, String status) {}

  /** 更新已入库文档：同文件名的新内容生成下一版本，解析完成后由前端替换草稿条目。 */
  @PostMapping("/spaces/{spaceId}/documents/{documentVersionId}/versions")
  public ResponseEntity<UpdateView> updateVersion(
      @PathVariable String spaceId,
      @PathVariable String documentVersionId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestParam("file") MultipartFile file) {
    AppUser user = current.require();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
    }
    limiter.checkWrites(user.id());
    ImportService.Updated updated = imports.updateDocument(
        user.id(), Http.pathUuid(spaceId, "spaceId"),
        Http.pathUuid(documentVersionId, "documentVersionId"), file, idempotencyKey);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header(HttpHeaders.LOCATION,
            "/api/v1/spaces/" + spaceId + "/documents/" + updated.documentVersionId())
        .body(new UpdateView(updated.documentVersionId(), updated.versionNumber(), updated.status()));
  }

  @PostMapping("/spaces/{spaceId}/documents/{documentVersionId}/retry")
  public ResponseEntity<DocumentPreview> retry(
      @PathVariable String spaceId,
      @PathVariable String documentVersionId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    AppUser user = current.require();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
    }
    UUID space = Http.pathUuid(spaceId, "spaceId");
    UUID document = Http.pathUuid(documentVersionId, "documentVersionId");
    imports.retry(user.id(), space, document);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header(HttpHeaders.LOCATION, "/api/v1/spaces/" + space + "/documents/" + document)
        .body(preview(user, space, document, true));
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record DocumentPreview(
      UUID documentVersionId, String name, String status, String previewText,
      Long sizeBytes, String mediaType, Integer versionNumber, String errorCode) {}

  @GetMapping("/spaces/{spaceId}/documents/{documentVersionId}")
  public DocumentPreview getDocument(@PathVariable String spaceId, @PathVariable String documentVersionId) {
    AppUser user = current.require();
    return preview(user, Http.pathUuid(spaceId, "spaceId"), Http.pathUuid(documentVersionId, "documentVersionId"), false);
  }

  private DocumentPreview preview(AppUser user, UUID spaceId, UUID documentId, boolean maintainer) {
    if (maintainer) {
      spaces.requireMaintain(spaceId, user.id());
      DocumentRepository.DocumentRow row = documents.find(spaceId, documentId).orElseThrow(ApiException::notFound);
      return view(row, text(row).orElse(null));
    }
    requireUsableRead(user, spaceId);
    DocumentRepository.DocumentRow row = documents.find(spaceId, documentId).orElseThrow(ApiException::notFound);
    if (!visibleToUser(user, spaceId, documentId)) {
      throw ApiException.notFound();
    }
    return view(row, text(row).orElse(null));
  }

  /**
   * 使用侧读取入口：普通用户仍是「成员 + 当前已发布」；系统管理员可读任意空间，
   * 供其核对未发布与已撤回内容（问答链路不在此放宽）。
   */
  private void requireUsableRead(AppUser user, UUID spaceId) {
    if (user.isSystemAdmin()) {
      spaces.requireVisible(user, spaceId);
      return;
    }
    spaces.requireReadable(spaceId, user.id());
  }

  private boolean visibleToUser(AppUser user, UUID spaceId, UUID documentId) {
    return user.isSystemAdmin()
        ? documents.inLatestPublicationOrDraft(spaceId, documentId)
        : documents.visibleInCurrentPublication(spaceId, documentId);
  }

  private static DocumentPreview view(DocumentRepository.DocumentRow row, String previewText) {
    return new DocumentPreview(
        row.id(), row.displayFilename(), row.parseState(), previewText,
        row.sizeBytes(), row.mediaType(), row.versionNumber(), row.errorCode());
  }

  private java.util.Optional<String> text(DocumentRepository.DocumentRow row) {
    List<DocumentRepository.ChunkRow> chunks = documents.chunks(row.spaceId(), row.id());
    if (chunks.isEmpty()) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(String.join("\n\n", chunks.stream().map(DocumentRepository.ChunkRow::body).toList()));
  }

  @GetMapping("/spaces/{spaceId}/documents/{documentVersionId}/content")
  public ResponseEntity<InputStreamResource> content(
      @PathVariable String spaceId, @PathVariable String documentVersionId) throws IOException {
    AppUser user = current.require();
    UUID space = Http.pathUuid(spaceId, "spaceId");
    UUID document = Http.pathUuid(documentVersionId, "documentVersionId");
    requireUsableRead(user, space);
    if (!visibleToUser(user, space, document)) {
      throw ApiException.notFound();
    }
    DocumentRepository.DocumentRow row = documents.find(space, document).orElseThrow(ApiException::notFound);
    InputStream stream = objects.get(row.objectKey());
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encode(row.displayFilename()))
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(new InputStreamResource(stream));
  }

  private static String encode(String name) {
    return java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
  }
}
