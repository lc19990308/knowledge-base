package dev.aiqa.worker;

import dev.aiqa.document.DocumentRepository;
import dev.aiqa.ingest.ChineseAnalyzer;
import dev.aiqa.ingest.TextExtractor;
import dev.aiqa.persistence.JobRepository;
import dev.aiqa.persistence.JobRepository.DocumentOutcome;
import dev.aiqa.storage.ObjectStore;
import java.io.InputStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the derived text index from stored bytes. It never changes a document's parse state or
 * the publication pointer, so a rebuild cannot make content visible or invisible.
 */
@Component
public class IndexRebuildWorker {

  private static final Logger log = LoggerFactory.getLogger(IndexRebuildWorker.class);

  private final JobRepository jobs;
  private final DocumentJobCompletion completion;
  private final DocumentRepository documents;
  private final ObjectStore objects;
  private final TextExtractor extractor;
  private final ChineseAnalyzer analyzer;
  private final dev.aiqa.search.EmbeddingClient embeddings;

  public IndexRebuildWorker(
      JobRepository jobs,
      DocumentJobCompletion completion,
      DocumentRepository documents,
      ObjectStore objects,
      TextExtractor extractor,
      ChineseAnalyzer analyzer,
      dev.aiqa.search.EmbeddingClient embeddings) {
    this.jobs = jobs;
    this.completion = completion;
    this.documents = documents;
    this.objects = objects;
    this.extractor = extractor;
    this.analyzer = analyzer;
    this.embeddings = embeddings;
  }

  @Scheduled(fixedDelayString = "${aiqa.worker.poll-ms:800}")
  public void poll() {
    try {
      for (int index = 0; index < 3; index++) {
        JobRepository.Job job = jobs.claimNext(JobRepository.Kind.INDEX, 300);
        if (job == null) {
          break;
        }
        try {
          List<DocumentRepository.IndexChunk> chunks = prepare(job);
          completion.finish(job, DocumentOutcome.SUCCEEDED, 0, null,
              () -> documents.replaceIndex(job.spaceId(), job.documentVersionId(), chunks));
        } catch (Exception ex) {
          log.warn("索引重建失败 job={} doc={}", job.id(), job.documentVersionId(), ex);
          completion.finish(job, DocumentOutcome.FAILED, 0, "INDEX_REBUILD_FAILED", () -> {});
        }
      }
    } catch (Exception ex) {
      log.warn("索引重建轮询异常", ex);
    }
  }

  private List<DocumentRepository.IndexChunk> prepare(JobRepository.Job job) throws Exception {
    DocumentRepository.DocumentRow row =
        documents.find(job.spaceId(), job.documentVersionId())
            .orElseThrow(() -> new IllegalStateException("文档已不存在"));
    TextExtractor.Extracted extracted;
    try (InputStream content = objects.get(row.objectKey())) {
      extracted = extractor.extract(row.displayFilename(), content);
    }
    if (extracted.chunks().isEmpty()) {
      throw new IllegalStateException("重建未提取到可索引的文档正文");
    }
    List<float[]> vectors =
        embeddings.embedAll(extracted.chunks().stream().map(TextExtractor.Chunk::body).toList());
    List<DocumentRepository.IndexChunk> chunks = new java.util.ArrayList<>(extracted.chunks().size());
    for (int index = 0; index < extracted.chunks().size(); index++) {
      TextExtractor.Chunk chunk = extracted.chunks().get(index);
      chunks.add(new DocumentRepository.IndexChunk(chunk.body(), chunk.sourceLocation(),
          analyzer.searchVectorLiteral(chunk.body()),
          index < vectors.size() ? vectors.get(index) : null));
    }
    return chunks;
  }
}
