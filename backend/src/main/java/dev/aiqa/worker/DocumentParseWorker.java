package dev.aiqa.worker;

import dev.aiqa.document.DocumentRepository;
import dev.aiqa.document.ImportRepository;
import dev.aiqa.ingest.ChineseAnalyzer;
import dev.aiqa.ingest.TextExtractor;
import dev.aiqa.persistence.JobRepository;
import dev.aiqa.persistence.JobRepository.DocumentOutcome;
import dev.aiqa.storage.ObjectStore;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DocumentParseWorker {

  private static final Logger log = LoggerFactory.getLogger(DocumentParseWorker.class);
  private static final int MAX_ATTEMPTS = 3;

  private final JobRepository jobs;
  private final DocumentJobCompletion completion;
  private final DocumentRepository documents;
  private final ImportRepository imports;
  private final ObjectStore objects;
  private final TextExtractor extractor;
  private final ChineseAnalyzer analyzer;
  private final dev.aiqa.search.EmbeddingClient embeddings;
  private final io.micrometer.core.instrument.MeterRegistry meters;

  public DocumentParseWorker(
      JobRepository jobs,
      DocumentJobCompletion completion,
      DocumentRepository documents,
      ImportRepository imports,
      ObjectStore objects,
      TextExtractor extractor,
      ChineseAnalyzer analyzer,
      dev.aiqa.search.EmbeddingClient embeddings,
      io.micrometer.core.instrument.MeterRegistry meters) {
    this.jobs = jobs;
    this.completion = completion;
    this.documents = documents;
    this.imports = imports;
    this.objects = objects;
    this.extractor = extractor;
    this.analyzer = analyzer;
    this.embeddings = embeddings;
    this.meters = meters;
  }

  @Scheduled(fixedDelayString = "${aiqa.worker.poll-ms:800}")
  public void poll() {
    try {
      jobs.requeueExpiredLeases();
      for (int index = 0; index < 5; index++) {
        JobRepository.Job job = jobs.claimNext(JobRepository.Kind.PARSE, 120);
        if (job == null) {
          break;
        }
        try {
          Runnable businessUpdate = prepare(job);
          completion.finish(job, DocumentOutcome.SUCCEEDED, 0, null, businessUpdate);
        } catch (Exception ex) {
          log.warn("文档解析失败 job={} doc={}", job.id(), job.documentVersionId(), ex);
          handleFailure(job);
        }
      }
    } catch (Exception ex) {
      log.warn("解析任务轮询异常", ex);
    }
  }

  private Runnable prepare(JobRepository.Job job) throws Exception {
    UUID spaceId = job.spaceId();
    UUID documentId = job.documentVersionId();
    DocumentRepository.DocumentRow row =
        documents.find(spaceId, documentId).orElseThrow(() -> new IllegalStateException("文档已不存在"));
    if ("CLEAN".equals(row.scanState()) || "REJECTED".equals(row.scanState())) {
      return () -> {};
    }
    TextExtractor.Extracted extracted;
    try (InputStream content = objects.get(row.objectKey())) {
      extracted = extractor.extract(row.displayFilename(), content);
    }
    if (extracted.chunks().isEmpty()) {
      return () -> {
        documents.markParsed(spaceId, documentId, "NO_TEXT", "NO_EXTRACTABLE_TEXT");
        imports.updateItemStatus(documentId, "NO_TEXT", "NO_EXTRACTABLE_TEXT");
      };
    }
    List<float[]> vectors = embeddings.embedAll(
        extracted.chunks().stream().map(TextExtractor.Chunk::body).toList());
    List<DocumentRepository.IndexChunk> chunks = new java.util.ArrayList<>(extracted.chunks().size());
    for (int index = 0; index < extracted.chunks().size(); index++) {
      TextExtractor.Chunk chunk = extracted.chunks().get(index);
      chunks.add(new DocumentRepository.IndexChunk(chunk.body(), chunk.sourceLocation(),
          analyzer.searchVectorLiteral(chunk.body()),
          index < vectors.size() ? vectors.get(index) : null));
    }
    return () -> {
      documents.completeParse(spaceId, documentId, chunks);
      imports.updateItemStatus(documentId, "READY", null);
    };
  }

  private void handleFailure(JobRepository.Job job) {
    String errorCode = "PROCESSING_FAILED";
    boolean recorded;
    if (job.attemptCount() < MAX_ATTEMPTS) {
      recorded = completion.finish(job, DocumentOutcome.QUEUED, 5 * job.attemptCount(), errorCode, () -> {});
    } else {
      recorded = completion.finish(job, DocumentOutcome.FAILED, 0, errorCode, () -> {
        documents.markParsed(job.spaceId(), job.documentVersionId(), "FAILED", errorCode);
        imports.updateItemStatus(job.documentVersionId(), "FAILED", errorCode);
      });
    }
    if (recorded) {
      meters.counter("aiqa.parse.failures").increment();
    }
  }
}
