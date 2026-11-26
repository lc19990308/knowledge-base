package dev.aiqa.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.aiqa.document.DocumentRepository;
import dev.aiqa.document.DocumentRepository.IndexChunk;
import dev.aiqa.persistence.JobRepository;
import dev.aiqa.storage.ObjectStore;
import dev.aiqa.testsupport.TestDatabase;
import dev.aiqa.worker.DocumentJobCompletion;
import dev.aiqa.worker.IndexRebuildWorker;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class IndexRebuildIT {
  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  static class TransactionConfig {}

  @Test
  void rebuildKeepsOldIndexOnEmptyExtractionAndFailedInsertThenReplacesItOnSuccess() throws Exception {
    var isolated = TestDatabase.fromEnvironment();
    Assumptions.assumeTrue(isolated.isPresent(), "未配置隔离测试数据库");
    try (var database = isolated.orElseThrow(); var context = new AnnotationConfigApplicationContext()) {
      DataSource source = new DriverManagerDataSource(database.jdbcUrl(), database.user(), database.password());
      Flyway.configure().dataSource(source).schemas(database.schema()).load().migrate();
      JdbcClient jdbc = JdbcClient.create(source);
      context.register(TransactionConfig.class);
      context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
      context.registerBean(DocumentRepository.class, () -> new DocumentRepository(jdbc));
      context.registerBean(JobRepository.class, () -> new JobRepository(jdbc, 500, 2500, 200, 200, 200, 200, 200, 50, 5, 2, 1, 1, 2, 1, 1));
      context.refresh();
      var documents = context.getBean(DocumentRepository.class);

      UUID user = UUID.randomUUID();
      UUID space = UUID.randomUUID();
      UUID doc = UUID.randomUUID();
      jdbc.sql("INSERT INTO app_user (id, issuer, subject, display_name) VALUES (?, 'test', 'owner', 'Owner')")
          .param(user).update();
      jdbc.sql("INSERT INTO space (id, owner_id, name) VALUES (?, ?, 'Policies')")
          .params(space, user).update();
      jdbc.sql("""
          INSERT INTO document_version (id, space_id, document_id, normalized_filename,
            display_filename, object_key, sha256, media_type, size_bytes, scan_state, parse_state,
            created_by)
          VALUES (?, ?, ?, 'policy.md', 'policy.md', 'blob', repeat('a', 64), 'text/markdown', 10,
            'PENDING', 'READY', ?)
          """).params(doc, space, doc, user).update();
      documents.replaceIndex(space, doc, List.of(new IndexChunk("旧制度", "旧章节", "旧 制度")));
      var old = documents.chunks(space, doc);

      // Reject the second insert, after replacement already deleted the old row and inserted the first.
      jdbc.sql("ALTER TABLE document_chunk ADD CHECK (ordinal <> 1)").update();
      var jobs = context.getBean(JobRepository.class);
      var objects = mock(ObjectStore.class);
      var completion = new DocumentJobCompletion(jobs, context.getBean(DataSourceTransactionManager.class));
      var worker = new IndexRebuildWorker(jobs, completion, documents, objects,
          new TextExtractor(), new ChineseAnalyzer(),
          new dev.aiqa.search.EmbeddingClient(false, "http://127.0.0.1:9/v1", "disabled",
              java.time.Duration.ofSeconds(1)));
      when(objects.get("blob")).thenReturn(bytes(" \n"), bytes("# 新规则\n正文\n# 次章\n内容"))
          .thenAnswer(invocation -> {
            jdbc.sql("UPDATE job SET lease_expires_at = clock_timestamp() WHERE status = 'RUNNING'").update();
            jobs.requeueExpiredLeases();
            jdbc.sql("UPDATE job SET available_at = now() WHERE status = 'QUEUED'").update();
            assertThat(jobs.claimNext(JobRepository.Kind.INDEX, 300)).isNotNull();
            return bytes("# 过期结果\n不应替换索引");
          }).thenReturn(bytes("# 超时结果\n不应替换索引"), bytes("# 新制度\n修订后的正文"));

      enqueue(context, jobs, space, doc);
      worker.poll();
      assertThat(jobCount(jdbc, "FAILED")).isEqualTo(1);
      assertThat(documents.chunks(space, doc)).isEqualTo(old);

      enqueue(context, jobs, space, doc);
      worker.poll();
      assertThat(jobCount(jdbc, "FAILED")).isEqualTo(2);
      assertThat(documents.chunks(space, doc)).isEqualTo(old);

      enqueue(context, jobs, space, doc);
      worker.poll();
      assertThat(jobCount(jdbc, "RUNNING")).isEqualTo(1);
      assertThat(jobCount(jdbc, "SUCCEEDED")).isZero();
      assertThat(documents.chunks(space, doc)).isEqualTo(old);
      jdbc.sql("UPDATE job SET lease_expires_at = clock_timestamp() WHERE status = 'RUNNING'").update();
      jobs.requeueExpiredLeases();
      jdbc.sql("UPDATE job SET available_at = now() WHERE status = 'QUEUED'").update();

      // Simulate an expiry caused by time spent inside the final business transaction.
      jdbc.sql("""
          CREATE FUNCTION expire_index_lease() RETURNS trigger LANGUAGE plpgsql AS $$
          BEGIN
            UPDATE job SET lease_expires_at = clock_timestamp() WHERE status = 'RUNNING';
            RETURN NEW;
          END; $$
          """).update();
      jdbc.sql("""
          CREATE TRIGGER expire_index_lease BEFORE INSERT ON document_chunk
            FOR EACH ROW EXECUTE FUNCTION expire_index_lease()
          """).update();
      worker.poll();
      assertThat(jobCount(jdbc, "RUNNING")).isEqualTo(1);
      assertThat(jobCount(jdbc, "SUCCEEDED")).isZero();
      assertThat(documents.chunks(space, doc)).isEqualTo(old);
      jdbc.sql("DROP TRIGGER expire_index_lease ON document_chunk").update();
      jdbc.sql("UPDATE job SET lease_expires_at = clock_timestamp() WHERE status = 'RUNNING'").update();
      jobs.requeueExpiredLeases();
      jdbc.sql("UPDATE job SET available_at = now() WHERE status = 'QUEUED'").update();

      worker.poll();
      assertThat(jobCount(jdbc, "SUCCEEDED")).isEqualTo(1);
      assertThat(documents.chunks(space, doc)).singleElement().satisfies(chunk -> {
        assertThat(chunk.id()).isNotEqualTo(old.get(0).id());
        assertThat(chunk.ordinal()).isZero();
        assertThat(chunk.body()).isEqualTo("# 新制度\n修订后的正文");
        assertThat(chunk.sourceLocation()).isEqualTo("新制度");
      });
      assertThat(documents.find(space, doc).orElseThrow().parseState()).isEqualTo("READY");
    }
  }

  private void enqueue(AnnotationConfigApplicationContext context, JobRepository jobs, UUID space, UUID doc) {
    var transactions = new org.springframework.transaction.support.TransactionTemplate(
        context.getBean(DataSourceTransactionManager.class));
    transactions.executeWithoutResult(status -> assertThat(jobs.enqueueIndex(doc, space)).isTrue());
  }

  private int jobCount(JdbcClient jdbc, String status) {
    return jdbc.sql("SELECT count(*) FROM job WHERE status = ?").param(status).query(Integer.class).single();
  }

  private ByteArrayInputStream bytes(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }
}
