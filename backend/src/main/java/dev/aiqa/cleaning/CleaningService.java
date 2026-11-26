package dev.aiqa.cleaning;

import dev.aiqa.auth.AppUser;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.Digests;
import dev.aiqa.common.ErrorCode;
import dev.aiqa.common.FileNames;
import dev.aiqa.persistence.IdempotencyRepository;
import dev.aiqa.persistence.JobRepository;
import dev.aiqa.space.SpaceService;
import dev.aiqa.storage.ObjectStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 受理清洗批次：校验数量与体积、安全解包、原件落对象存储，然后登记批次并投递清洗任务。
 *
 * <p>文件先暂存对象存储、再在一个事务里落库；任何一步失败都会释放幂等键并清掉本轮暂存的对象，
 * 不留下没有批次引用的孤儿文件。
 */
@Service
public class CleaningService {

  private static final Logger log = LoggerFactory.getLogger(CleaningService.class);
  private static final String UPLOAD_OPERATION = "CLEANING_UPLOAD";
  private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter NAME_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(BEIJING);

  /** 单批最多文件数（文件夹文件 + 压缩包内受支持条目）。 */
  public static final int MAX_FILES = 500;
  /** 单文件上限，压缩包内单个条目同此限制。 */
  public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
  /** 整批上限，压缩包内解压总量同此限制。 */
  public static final long MAX_BATCH_BYTES = 200L * 1024 * 1024;
  /** 压缩包上传体积上限（未解压）。 */
  public static final long MAX_ARCHIVE_BYTES = 64L * 1024 * 1024;

  private final CleaningRepository repository;
  private final SpaceService spaces;
  private final ObjectStore objects;
  private final CleaningZip zip;
  private final JobRepository jobs;
  private final IdempotencyRepository idempotency;
  private final CleaningModelScorer modelScorer;
  private final TransactionTemplate transactions;
  private final long maxBatchBytes;

  public CleaningService(
      CleaningRepository repository,
      SpaceService spaces,
      ObjectStore objects,
      CleaningZip zip,
      JobRepository jobs,
      IdempotencyRepository idempotency,
      CleaningModelScorer modelScorer,
      PlatformTransactionManager transactionManager,
      @Value("${aiqa.cleaning.max-batch-bytes:209715200}") long maxBatchBytes) {
    this.repository = repository;
    this.spaces = spaces;
    this.objects = objects;
    this.zip = zip;
    this.jobs = jobs;
    this.idempotency = idempotency;
    this.modelScorer = modelScorer;
    this.transactions = new TransactionTemplate(transactionManager);
    this.maxBatchBytes = Math.min(maxBatchBytes, MAX_BATCH_BYTES);
  }

  /** 暂存后的来源文件；提交成功后对象被批次引用，失败则删除。 */
  private record StagedSource(
      String relativePath, String displayName, String mediaType, long sizeBytes, String sha256,
      String objectKey) {}

  /**
   * 受理一次清洗上传。
   *
   * @param targetSpaceId 预选目标空间，可为空；非空时要求调用者对该空间有维护权限
   * @param files 文件夹里的文件（可为空）
   * @param paths 与 files 同序的相对路径；缺省时退回原始文件名
   * @param archive 可选压缩包
   */
  @Transactional(propagation = Propagation.NEVER)
  public CleaningRepository.BatchRow upload(
      AppUser user,
      UUID targetSpaceId,
      String name,
      List<MultipartFile> files,
      List<String> paths,
      MultipartFile archive,
      String idempotencyKey) {
    if (targetSpaceId != null) {
      spaces.requireMaintain(targetSpaceId, user.id());
    }
    List<MultipartFile> uploaded = files == null ? List.of() : files;
    boolean hasArchive = archive != null && !archive.isEmpty();
    if (uploaded.isEmpty() && !hasArchive) {
      throw ApiException.invalid("至少上传一个文件或压缩包");
    }
    if (uploaded.size() > MAX_FILES) {
      throw tooLarge("单批最多 " + MAX_FILES + " 个文件");
    }
    long declaredBytes =
        uploaded.stream().mapToLong(MultipartFile::getSize).sum()
            + (hasArchive ? archive.getSize() : 0);
    if (declaredBytes > maxBatchBytes) {
      throw tooLarge("整批超过 200 MB，请分批上传");
    }
    if (hasArchive && archive.getSize() > MAX_ARCHIVE_BYTES) {
      throw tooLarge("压缩包超过 64 MB，请拆分后再上传");
    }
    String digest = fingerprint(user.id(), targetSpaceId, name, uploaded, paths, archive);
    IdempotencyRepository.Reservation reservation =
        idempotency.reserve(user.id(), UPLOAD_OPERATION, idempotencyKey, digest);
    if (reservation.replay()) {
      return repository.findBatch(reservation.resourceId()).orElseThrow(ApiException::notFound);
    }

    List<String> stagedKeys = new ArrayList<>();
    boolean committed = false;
    try {
      List<StagedSource> staged = new ArrayList<>();
      int skipped = 0;
      for (int index = 0; index < uploaded.size(); index++) {
        MultipartFile file = uploaded.get(index);
        String rawPath =
            paths != null && index < paths.size() ? paths.get(index) : file.getOriginalFilename();
        String path = normalizePath(rawPath);
        if (!RawDatasetReader.isSupported(FileNames.extension(path))) {
          skipped++;
          continue;
        }
        if (file.getSize() > MAX_FILE_BYTES) {
          throw tooLarge(displayName(path) + " 超过单文件 10 MB");
        }
        staged.add(stage(path, bytesOf(file), stagedKeys));
      }
      if (hasArchive) {
        CleaningZip.Result result;
        try {
          result = zip.extract(bytesOf(archive));
        } catch (ZipRejectedException ex) {
          throw new ApiException(
              ErrorCode.CLEANING_ARCHIVE_REJECTED,
              HttpStatus.UNPROCESSABLE_ENTITY,
              archiveMessage(ex.code()));
        }
        skipped += result.skipped();
        for (CleaningZip.Entry entry : result.entries()) {
          staged.add(stage(entry.path(), entry.content(), stagedKeys));
        }
      }
      if (staged.isEmpty()) {
        throw ApiException.invalid("没有可清洗的文件：只支持 TXT、MD、CSV、TSV、JSON、JSONL、HTML、XLSX、PDF、DOCX、PPTX");
      }
      long stagedBytes = staged.stream().mapToLong(StagedSource::sizeBytes).sum();
      if (stagedBytes > maxBatchBytes) {
        throw tooLarge("解出的内容超过 200 MB，请分批上传");
      }
      String batchName = batchName(name, staged, hasArchive ? archive.getOriginalFilename() : null);
      List<StagedSource> accepted = List.copyOf(staged);
      int skippedCount = skipped;
      CleaningRepository.BatchRow batch =
          transactions.execute(
              status ->
                  commit(user, targetSpaceId, batchName, accepted, skippedCount, idempotencyKey));
      committed = true;
      return batch;
    } catch (RuntimeException ex) {
      try {
        idempotency.release(user.id(), UPLOAD_OPERATION, idempotencyKey);
      } catch (RuntimeException releaseFailure) {
        ex.addSuppressed(releaseFailure);
      }
      throw ex;
    } finally {
      if (!committed) {
        for (String key : stagedKeys) {
          discard(key);
        }
      }
    }
  }

  /** 在一个事务里登记批次、来源文件与清洗任务，并完成幂等键。 */
  private CleaningRepository.BatchRow commit(
      AppUser user,
      UUID targetSpaceId,
      String name,
      List<StagedSource> staged,
      int skipped,
      String idempotencyKey) {
    UUID batchId = UUID.randomUUID();
    boolean modelScoring = modelScorer.enabled();
    repository.createBatch(
        new CleaningRepository.BatchRow(
            batchId, name, targetSpaceId, user.id(), "QUEUED", modelScoring, staged.size(),
            skipped, 0, 0, 0, null, null, java.time.OffsetDateTime.now(), null));
    List<CleaningRepository.SourceFileRow> rows = new ArrayList<>(staged.size());
    for (int ordinal = 0; ordinal < staged.size(); ordinal++) {
      StagedSource source = staged.get(ordinal);
      rows.add(
          new CleaningRepository.SourceFileRow(
              UUID.randomUUID(), batchId, ordinal, source.relativePath(), source.displayName(),
              source.mediaType(), source.sizeBytes(), source.sha256(), source.objectKey(),
              "PENDING", 0, null));
    }
    repository.insertSourceFiles(rows);
    jobs.requireCapacity(JobRepository.Kind.CLEAN, 1);
    jobs.enqueueCleaning(batchId, targetSpaceId);
    idempotency.complete(user.id(), UPLOAD_OPERATION, idempotencyKey, batchId, 202);
    return repository.findBatch(batchId).orElseThrow(ApiException::notFound);
  }

  /** 读取上传内容；体积已在调用前校验，这里只兜 IOException。 */
  private static byte[] bytesOf(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException ex) {
      throw new IllegalStateException("读取上传文件失败", ex);
    }
  }

  /** 把内容写进对象存储，并记录键用于失败清理。 */
  private StagedSource stage(String path, byte[] content, List<String> stagedKeys) {
    String key = UUID.randomUUID().toString();
    stagedKeys.add(key);
    try {
      objects.put(key, new ByteArrayInputStream(content), content.length);
    } catch (IOException ex) {
      throw new IllegalStateException("暂存原始文件失败", ex);
    }
    return new StagedSource(
        path,
        displayName(path),
        RawDatasetReader.mediaTypeOf(FileNames.extension(path)),
        content.length,
        Digests.sha256Hex(content),
        key);
  }

  private void discard(String key) {
    try {
      objects.delete(key);
    } catch (IOException | RuntimeException ex) {
      log.warn("清洗原件清理失败 object={}", key, ex);
    }
  }

  /** 文件夹上传的相对路径：拒绝路径穿越与盘符，保留层级。 */
  private static String normalizePath(String raw) {
    String path = CleaningZip.normalize(raw);
    if (path == null) {
      throw ApiException.invalid("文件路径不安全，请重新选择文件夹");
    }
    return path;
  }

  /** 展示名只取最后一段，避免表格里出现整条路径。 */
  private static String displayName(String path) {
    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  /** 批次名优先级：用户填写 → 压缩包名 → 首个文件夹 → 时间戳。 */
  private static String batchName(String name, List<StagedSource> staged, String archiveName) {
    if (name != null && !name.isBlank()) {
      return truncate(name.strip(), 200);
    }
    if (archiveName != null && !archiveName.isBlank()) {
      return truncate(archiveName.replaceFirst("(?i)\\.zip$", ""), 200);
    }
    String firstPath = staged.get(0).relativePath();
    int slash = firstPath.indexOf('/');
    if (slash > 0) {
      return truncate(firstPath.substring(0, slash), 200);
    }
    return "清洗批次-" + NAME_STAMP.format(java.time.Instant.now());
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }

  /** 幂等指纹：路径与体积足够区分同一键下的不同请求，不读取文件内容。 */
  private static String fingerprint(
      UUID userId,
      UUID targetSpaceId,
      String name,
      List<MultipartFile> files,
      List<String> paths,
      MultipartFile archive) {
    StringBuilder buffer = new StringBuilder("cleaning-upload-v1");
    buffer.append('|').append(userId);
    buffer.append('|').append(targetSpaceId);
    buffer.append('|').append(name == null ? "" : name.strip());
    for (int index = 0; index < files.size(); index++) {
      MultipartFile file = files.get(index);
      String path = paths != null && index < paths.size() ? paths.get(index) : file.getOriginalFilename();
      buffer.append('|').append(path).append(':').append(file.getSize());
    }
    if (archive != null && !archive.isEmpty()) {
      buffer.append("|archive:").append(archive.getOriginalFilename())
          .append(':').append(archive.getSize());
    }
    return Digests.sha256Hex(buffer.toString());
  }

  private static ApiException tooLarge(String detail) {
    return new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE, detail);
  }

  /** 压缩包拒绝原因的用户可读说明。 */
  private static String archiveMessage(String code) {
    return switch (code) {
      case "ARCHIVE_TOO_MANY_ENTRIES" -> "压缩包条目超过 2000 个，请拆分后再上传。";
      case "ARCHIVE_ENTRY_TOO_LARGE" -> "压缩包内单个文件超过 10 MB。";
      case "ARCHIVE_RATIO_ANOMALY" -> "压缩比异常，已拒绝以防压缩炸弹。";
      case "ARCHIVE_TOO_LARGE" -> "压缩包解压后超过 200 MB，请拆分后再上传。";
      case "ARCHIVE_UNSAFE_PATH" -> "压缩包内含不安全的路径，已拒绝。";
      default -> "压缩包无法读取：请确认不是加密包或已损坏。";
    };
  }
}
