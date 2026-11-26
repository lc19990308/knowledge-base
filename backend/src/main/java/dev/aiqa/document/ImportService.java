package dev.aiqa.document;

import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import dev.aiqa.common.FileNames;
import dev.aiqa.ingest.FileScanner;
import dev.aiqa.persistence.IdempotencyRepository;
import dev.aiqa.persistence.JobRepository;
import dev.aiqa.space.SpaceRepository;
import dev.aiqa.space.SpaceService;
import dev.aiqa.storage.ObjectStore;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Registers a batch and its immutable document versions. Bytes are staged before the database
 * transaction; a losing concurrent duplicate never replaces an already-stored object.
 */
@Service
public class ImportService {

  private static final Logger log = LoggerFactory.getLogger(ImportService.class);
  private static final String IMPORT_OPERATION = "IMPORT_DOCUMENTS";
  private static final String UPDATE_OPERATION = "UPDATE_DOCUMENT";

  public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
  public static final int MAX_FILES = 10;
  public static final long MAX_BATCH_BYTES = 200L * 1024 * 1024;

  private final SpaceService spaces;
  private final SpaceRepository spaceRows;
  private final DocumentRepository documents;
  private final ImportRepository imports;
  private final ObjectStore objects;
  private final JobRepository jobs;
  private final IdempotencyRepository idempotency;
  private final FileScanner scanner;
  private final long maxBatchBytes;
  private final TransactionTemplate transactions;

  public ImportService(
      SpaceService spaces,
      SpaceRepository spaceRows,
      DocumentRepository documents,
      ImportRepository imports,
      ObjectStore objects,
      JobRepository jobs,
      IdempotencyRepository idempotency,
      FileScanner scanner,
      PlatformTransactionManager transactionManager,
      @Value("${aiqa.import.max-batch-bytes:209715200}") long maxBatchBytes) {
    this.spaces = spaces;
    this.spaceRows = spaceRows;
    this.documents = documents;
    this.imports = imports;
    this.objects = objects;
    this.jobs = jobs;
    this.idempotency = idempotency;
    this.scanner = scanner;
    this.maxBatchBytes = maxBatchBytes;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public record Accepted(UUID batchId, List<ImportRepository.Item> items) {}

  @Transactional(propagation = Propagation.NEVER)
  public Accepted importFiles(UUID userId, UUID spaceId, List<MultipartFile> files, String idempotencyKey) {
    spaces.requireMaintain(spaceId, userId);
    if (files == null || files.isEmpty()) {
      throw ApiException.invalid("至少上传一个文件");
    }
    if (files.size() > MAX_FILES) {
      throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE, "单批次最多 " + MAX_FILES + " 个文件");
    }
    long total = files.stream().mapToLong(MultipartFile::getSize).sum();
    if (total > Math.min(maxBatchBytes, MAX_BATCH_BYTES)) {
      throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE, "批次总大小超限");
    }
    List<CheckedFile> checked = files.stream().map(this::check).toList();
    String digest = fingerprint(spaceId, checked);
    var reservation = idempotency.reserve(userId, IMPORT_OPERATION, idempotencyKey, digest);
    if (reservation.replay()) {
      ImportRepository.Batch batch = imports.findBatch(reservation.resourceId()).orElseThrow(ApiException::notFound);
      return new Accepted(batch.id(), batch.items());
    }
    List<String> freshKeys = new ArrayList<>();
    Set<String> referencedKeys = new HashSet<>();
    Set<String> uncertainKeys = new HashSet<>();
    try {
      List<Prepared> prepared = new ArrayList<>();
      for (CheckedFile file : checked) {
        prepared.add(stage(file, freshKeys));
      }
      return transactions.execute(status -> {
        Set<String> insertedKeys = new HashSet<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
          @Override
          public void afterCompletion(int completionStatus) {
            if (completionStatus == STATUS_COMMITTED) {
              referencedKeys.addAll(insertedKeys);
            } else if (completionStatus == STATUS_UNKNOWN) {
              uncertainKeys.addAll(insertedKeys);
            }
          }
        });
        Accepted accepted = commit(userId, spaceId, prepared, insertedKeys);
        idempotency.complete(userId, IMPORT_OPERATION, idempotencyKey, accepted.batchId(), 202);
        return accepted;
      });
    } catch (RuntimeException ex) {
      try {
        idempotency.release(userId, IMPORT_OPERATION, idempotencyKey);
      } catch (RuntimeException releaseFailure) {
        ex.addSuppressed(releaseFailure);
      }
      throw ex;
    } finally {
      if (!uncertainKeys.isEmpty()) {
        referencedKeys.addAll(recoverReferencedKeys(spaceId, uncertainKeys));
      }
      for (String key : freshKeys) {
        if (!referencedKeys.contains(key)) {
          discard(key);
        }
      }
    }
  }

  private Set<String> recoverReferencedKeys(UUID spaceId, Set<String> uncertainKeys) {
    try {
      return transactions.execute(status -> {
        // Wait for the original transaction's space lock before checking its committed rows.
        spaceRows.lockSpace(spaceId);
        Set<String> referenced = new HashSet<>();
        for (String key : uncertainKeys) {
          if (documents.referencesObject(key)) {
            referenced.add(key);
          }
        }
        return referenced;
      });
    } catch (RuntimeException ex) {
      log.warn("无法确认暂存文件是否已被引用 space={}", spaceId, ex);
      return uncertainKeys;
    }
  }

  private CheckedFile check(MultipartFile file) {
    String submitted = file.getOriginalFilename();
    if (submitted == null || submitted.isBlank()) {
      throw ApiException.invalid("文件名不能为空");
    }
    String normalized = FileNames.normalize(submitted);
    String display = FileNames.displayName(submitted);
    long size = file.getSize();
    if (size > MAX_FILE_BYTES) {
      throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE, display + " 超过单文件上限");
    }
    MessageDigest contentDigest = sha256();
    byte[] prefix;
    try (var input = new DigestInputStream(file.getInputStream(), contentDigest)) {
      prefix = input.readNBytes(8192);
      if (prefix.length + input.transferTo(OutputStream.nullOutputStream()) != size) {
        throw ApiException.invalid("上传文件大小与内容不一致");
      }
    } catch (IOException ex) {
      throw new IllegalStateException("读取上传文件失败", ex);
    }
    String contentType = file.getContentType();
    return new CheckedFile(file, submitted, normalized, display,
        contentType == null ? "application/octet-stream" : contentType,
        size, HexFormat.of().formatHex(contentDigest.digest()), scanner.scan(display, prefix));
  }

  private static String fingerprint(UUID spaceId, List<CheckedFile> files) {
    MessageDigest digest = sha256();
    addField(digest, "multipart-import-v1");
    addField(digest, spaceId.toString());
    addField(digest, Integer.toString(files.size()));
    for (CheckedFile file : files) {
      addField(digest, file.submitted());
      addField(digest, file.mediaType());
      addField(digest, Long.toString(file.size()));
      addField(digest, file.sha256());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void addField(MessageDigest digest, String field) {
    byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 不可用", ex);
    }
  }

  private record CheckedFile(
      MultipartFile multipart, String submitted, String normalized, String display,
      String mediaType, long size, String sha256, FileScanner.Result scan) {}

  private record Prepared(CheckedFile file, String objectKey) {}

  private Prepared stage(CheckedFile file, List<String> freshKeys) {
    if (file.scan().verdict() == FileScanner.Verdict.REJECTED) {
      return new Prepared(file, null);
    }
    String key = UUID.randomUUID().toString();
    MessageDigest storedDigest = sha256();
    try (var input = new DigestInputStream(file.multipart().getInputStream(), storedDigest)) {
      // The store can persist the bytes and still throw, so track the key before attempting put.
      freshKeys.add(key);
      objects.put(key, input, file.size());
      if (!file.sha256().equals(HexFormat.of().formatHex(storedDigest.digest()))) {
        throw new IllegalStateException("暂存内容与上传文件不一致");
      }
      return new Prepared(file, key);
    } catch (IOException ex) {
      throw new IllegalStateException("暂存上传文件失败", ex);
    }
  }

  private void discard(String key) {
    try {
      objects.delete(key);
    } catch (IOException | RuntimeException ex) {
      log.warn("暂存文件清理失败 object={}", key, ex);
    }
  }

  private Accepted commit(
      UUID userId, UUID spaceId, List<Prepared> prepared, Set<String> insertedKeys) {
    spaceRows.lockSpace(spaceId);
    UUID batchId = imports.createBatch(spaceId, userId);
    List<ImportRepository.Item> items = new ArrayList<>();
    int ordinal = 0;
    for (Prepared entry : prepared) {
      CheckedFile file = entry.file();
      ImportRepository.Item item;
      if (entry.objectKey() == null) {
        item = new ImportRepository.Item(
            ordinal, file.submitted(), file.normalized(), "REJECTED", null, file.scan().code(),
            null, null, null);
      } else if (documents.findByNormalizedName(spaceId, file.normalized()).isPresent()) {
        item = new ImportRepository.Item(
            ordinal, file.submitted(), file.normalized(), "SKIPPED_DUPLICATE", null, "DUPLICATE_NAME",
            null, null, null);
      } else {
        UUID documentId = UUID.randomUUID();
        insertedKeys.add(entry.objectKey());
        documents.insert(
            new DocumentRepository.DocumentRow(
                documentId, spaceId, documentId, file.normalized(), file.display(), entry.objectKey(),
                file.sha256(), file.mediaType(), file.size(), "PENDING", "QUEUED", 0, null,
                nextVersionNumber(spaceId, file.normalized()), null),
            userId);
        jobs.requireCapacity(JobRepository.Kind.PARSE, 1);
        jobs.enqueueParse(documentId, spaceId);
        item = new ImportRepository.Item(
            ordinal, file.submitted(), file.normalized(), "QUEUED", documentId, null,
            file.size(), file.mediaType(), 1);
      }
      imports.addItem(batchId, spaceId, item);
      items.add(item);
      ordinal++;
    }
    spaceRows.bumpState(spaceId);
    return new Accepted(batchId, items);
  }

  /** 更新结果：新版本 id、版本号与解析状态。 */
  public record Updated(UUID documentVersionId, int versionNumber, String status) {}

  /**
   * 更新已入库文档：同一份文档追加一个新版本，旧版本与历史引用保持不变。
   *
   * <p>要求文件名与目标文档一致——改名属于另一份文档，应走导入；扫描不通过的文件不会入库。
   */
  @Transactional(propagation = Propagation.NEVER)
  public Updated updateDocument(
      UUID userId, UUID spaceId, UUID documentVersionId, MultipartFile file, String idempotencyKey) {
    spaces.requireMaintain(spaceId, userId);
    DocumentRepository.DocumentRow target =
        documents.find(spaceId, documentVersionId).orElseThrow(ApiException::notFound);
    if (file == null || file.isEmpty()) {
      throw ApiException.invalid("请选择要更新的文件");
    }
    CheckedFile checked = check(file);
    if (!target.normalizedFilename().equals(checked.normalized())) {
      throw ApiException.invalid("更新须保持文件名一致；如需改名，请作为新文档导入");
    }
    String digest = dev.aiqa.common.Digests.sha256Hex(
        "update|" + spaceId + "|" + documentVersionId + "|" + checked.sha256());
    var reservation = idempotency.reserve(userId, UPDATE_OPERATION, idempotencyKey, digest);
    if (reservation.replay()) {
      return documents.find(spaceId, reservation.resourceId())
          .map(row -> new Updated(row.id(), row.versionNumber(), row.parseState()))
          .orElseThrow(ApiException::notFound);
    }
    List<String> freshKeys = new ArrayList<>();
    Set<String> referencedKeys = new HashSet<>();
    try {
      Prepared prepared = stage(checked, freshKeys);
      if (prepared.objectKey() == null) {
        throw ApiException.invalid("文件未通过安全检查，不能更新");
      }
      return transactions.execute(status -> {
        spaceRows.lockSpace(spaceId);
        UUID nextId = UUID.randomUUID();
        int version = documents.nextVersionNumberForDocument(target.documentId());
        documents.insert(
            new DocumentRepository.DocumentRow(
                nextId, spaceId, target.documentId(), checked.normalized(), checked.display(),
                prepared.objectKey(), checked.sha256(), checked.mediaType(), checked.size(),
                "PENDING", "QUEUED", 0, null, version, null),
            userId);
        jobs.requireCapacity(JobRepository.Kind.PARSE, 1);
        jobs.enqueueParse(nextId, spaceId);
        spaceRows.bumpState(spaceId);
        referencedKeys.add(prepared.objectKey());
        idempotency.complete(userId, UPDATE_OPERATION, idempotencyKey, nextId, 202);
        return new Updated(nextId, version, "QUEUED");
      });
    } catch (RuntimeException ex) {
      try {
        idempotency.release(userId, UPDATE_OPERATION, idempotencyKey);
      } catch (RuntimeException releaseFailure) {
        ex.addSuppressed(releaseFailure);
      }
      throw ex;
    } finally {
      for (String key : freshKeys) {
        if (!referencedKeys.contains(key)) {
          discard(key);
        }
      }
    }
  }

  /** 同一空间同名文件的版本号递增，供界面展示 v1/v2/v3。 */
  private int nextVersionNumber(UUID spaceId, String normalized) {
    return imports.nextVersionNumber(spaceId, normalized);
  }

  public ImportRepository.Batch findBatch(UUID userId, UUID batchId) {
    UUID spaceId = imports.batchSpace(batchId).orElseThrow(ApiException::notFound);
    spaces.requireMaintain(spaceId, userId);
    return imports.findBatch(batchId).orElseThrow(ApiException::notFound);
  }

  @Transactional
  public void retry(UUID userId, UUID spaceId, UUID documentId) {
    spaces.requireMaintain(spaceId, userId);
    spaceRows.lockSpace(spaceId);
    DocumentRepository.DocumentRow row =
        documents.find(spaceId, documentId).orElseThrow(ApiException::notFound);
    if (!"FAILED".equals(row.parseState())) {
      throw ApiException.conflict(ErrorCode.DOCUMENT_NOT_RETRYABLE);
    }
    jobs.requireCapacity(JobRepository.Kind.PARSE, 1);
    documents.resetForRetry(spaceId, documentId);
    imports.updateItemStatus(documentId, "QUEUED", null);
    jobs.enqueueParse(documentId, spaceId);
  }
}
