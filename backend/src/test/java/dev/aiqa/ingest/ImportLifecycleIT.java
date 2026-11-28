package dev.aiqa.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import dev.aiqa.document.DocumentRepository;
import dev.aiqa.document.ImportRepository;
import dev.aiqa.document.ImportService;
import dev.aiqa.persistence.IdempotencyRepository;
import dev.aiqa.persistence.JobRepository;
import dev.aiqa.space.SpaceRepository;
import dev.aiqa.space.SpaceService;
import dev.aiqa.storage.LocalObjectStore;
import dev.aiqa.testsupport.TestDatabase;
import dev.aiqa.worker.DocumentJobCompletion;
import dev.aiqa.worker.DocumentParseWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class ImportLifecycleIT {
  @TempDir Path temp;

  @EnableTransactionManagement
  static class TransactionConfig {}

  @Test
  void importFingerprintAndCleanupProtectCommittedObjects() throws Exception {
    var isolated = TestDatabase.fromEnvironment();
    Assumptions.assumeTrue(isolated.isPresent(), "未配置隔离测试数据库");
    try (var database = isolated.orElseThrow(); var context = new AnnotationConfigApplicationContext()) {
      var fixture = fixture(database, context, new ErrorAfterPutStore(temp));
      var imports = fixture.imports();
      var original = file("shared.md", "text/markdown", "first");
      var accepted = imports.importFiles(fixture.user(), fixture.space(), List.of(original), "upload-first");
      String committedKey = fixture.jdbc().sql("SELECT object_key FROM document_version").query(String.class).single();
      assertThat(imports.importFiles(fixture.user(), fixture.space(), List.of(original), "upload-first").batchId())
          .isEqualTo(accepted.batchId());
      assertThatThrownBy(() -> imports.importFiles(fixture.user(), fixture.space(),
          List.of(file("shared.md", "text/markdown", "other")), "upload-first"))
          .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
      assertThatThrownBy(() -> imports.importFiles(fixture.user(), fixture.space(),
          List.of(file("shared.md", "text/plain", "first")), "upload-first"))
          .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
      assertThat(imports.importFiles(fixture.user(), fixture.space(), List.of(original, original), "upload-duplicate")
          .items()).extracting(ImportRepository.Item::status).containsExactly("SKIPPED_DUPLICATE", "SKIPPED_DUPLICATE");
      assertFiles(committedKey);

      var next = file("new.md", "text/markdown", "next");
      assertThatThrownBy(() -> imports.importFiles(fixture.user(), fixture.space(),
          List.of(next, file("", "text/markdown", "invalid")), "upload-validation"))
          .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
      assertFiles(committedKey);

      ((ErrorAfterPutStore) fixture.objects()).remainingPuts = 2;
      assertThatThrownBy(() -> imports.importFiles(fixture.user(), fixture.space(),
          List.of(next, file("second.md", "text/markdown", "second")), "upload-interrupted"))
          .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(IOException.class);
      assertFiles(committedKey);

      // A deferred constraint triggers at commit, after both documents and their jobs were written.
      fixture.jdbc().sql("""
          CREATE FUNCTION fail_import_commit() RETURNS trigger LANGUAGE plpgsql AS $$
          BEGIN RAISE EXCEPTION 'test commit failure'; END; $$
          """).update();
      fixture.jdbc().sql("""
          CREATE CONSTRAINT TRIGGER fail_import_commit AFTER INSERT ON import_batch
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_import_commit()
          """).update();
      assertThatThrownBy(() -> imports.importFiles(fixture.user(), fixture.space(), List.of(next), "upload-interrupted"))
          .hasStackTraceContaining("test commit failure");
      assertFiles(committedKey);
      assertThat(fixture.jdbc().sql("SELECT count(*) FROM job").query(Integer.class).single()).isEqualTo(1);
      fixture.jdbc().sql("DROP TRIGGER fail_import_commit ON import_batch").update();
      assertThat(imports.importFiles(fixture.user(), fixture.space(), List.of(next), "upload-interrupted").items())
          .extracting(ImportRepository.Item::status).containsExactly("QUEUED");
      assertThat(Files.readString(temp.resolve(committedKey))).isEqualTo("first");
    }
  }

  @Test
  void staleTerminalParseFailureCannotOverwriteReclaimedAttempt() throws Exception {
    var isolated = TestDatabase.fromEnvironment();
    Assumptions.assumeTrue(isolated.isPresent(), "未配置隔离测试数据库");
    try (var database = isolated.orElseThrow(); var context = new AnnotationConfigApplicationContext()) {
      var fixture = fixture(database, context, new LocalObjectStore(temp.toString()) {
        @Override
        public InputStream get(String key) throws IOException {
          var jdbc = JdbcClient.create(context.getBean(DataSourceTransactionManager.class).getDataSource());
          jdbc.sql("UPDATE job SET lease_expires_at = clock_timestamp() WHERE status = 'RUNNING'").update();
          var jobs = context.getBean(JobRepository.class);
          jobs.requeueExpiredLeases();
          jdbc.sql("UPDATE job SET available_at = now() WHERE status = 'QUEUED'").update();
          assertThat(jobs.claimNext(JobRepository.Kind.PARSE, 120).attemptCount()).isEqualTo(4);
          throw new IOException("stale parse failed");
        }
      });
      var accepted = fixture.imports().importFiles(fixture.user(), fixture.space(),
          List.of(file("parse.md", "text/markdown", "content")), "upload-parse");
      UUID doc = accepted.items().get(0).documentVersionId();
      fixture.jdbc().sql("UPDATE job SET attempt_count = 2").update();
      var jobs = context.getBean(JobRepository.class);
      var documents = context.getBean(DocumentRepository.class);
      var importRows = context.getBean(ImportRepository.class);
      var completion = new DocumentJobCompletion(jobs, context.getBean(DataSourceTransactionManager.class));
      new DocumentParseWorker(jobs, completion, documents, importRows, fixture.objects(),
          new TextExtractor(), new ChineseAnalyzer(),
          new dev.aiqa.search.EmbeddingClient(false, "http://127.0.0.1:9/v1", "disabled",
              java.time.Duration.ofSeconds(1)),
          new SimpleMeterRegistry()).poll();
      var row = documents.find(fixture.space(), doc).orElseThrow();
      assertThat(row.parseState()).isEqualTo("QUEUED");
      assertThat(row.tryCount()).isZero();
      assertThat(importRows.findBatch(accepted.batchId()).orElseThrow().items())
          .extracting(ImportRepository.Item::status).containsExactly("QUEUED");
      assertThat(fixture.jdbc().sql("SELECT status FROM job").query(String.class).single()).isEqualTo("RUNNING");
    }
  }

  private Fixture fixture(TestDatabase database, AnnotationConfigApplicationContext context,
      LocalObjectStore objects) {
    DataSource source = new DriverManagerDataSource(database.jdbcUrl(), database.user(), database.password());
    Flyway.configure().dataSource(source).schemas(database.schema()).load().migrate();
    JdbcClient jdbc = JdbcClient.create(source);
    context.register(TransactionConfig.class);
    context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
    context.registerBean(JobRepository.class, () -> new JobRepository(jdbc, 500, 2500, 200, 200, 200, 200, 200, 50, 5, 2, 1, 1, 2, 1, 1));
    context.registerBean(
        DocumentRepository.class,
        () -> new DocumentRepository(
            jdbc, new dev.aiqa.search.VectorIndex(jdbc, new io.micrometer.core.instrument.simple.SimpleMeterRegistry())));
    context.registerBean(ImportRepository.class, () -> new ImportRepository(jdbc));
    context.registerBean(ImportService.class, () -> new ImportService(mock(SpaceService.class),
        new SpaceRepository(jdbc), context.getBean(DocumentRepository.class), context.getBean(ImportRepository.class),
        objects, context.getBean(JobRepository.class), new IdempotencyRepository(jdbc), new FileScanner(),
        context.getBean(DataSourceTransactionManager.class), ImportService.MAX_BATCH_BYTES));
    context.refresh();
    UUID user = UUID.randomUUID();
    UUID space = UUID.randomUUID();
    jdbc.sql("INSERT INTO app_user (id, issuer, subject, display_name) VALUES (?, 'test', 'owner', 'Owner')")
        .param(user).update();
    jdbc.sql("INSERT INTO space (id, owner_id, name) VALUES (?, ?, 'Policies')").params(space, user).update();
    return new Fixture(jdbc, user, space, context.getBean(ImportService.class), objects);
  }

  private void assertFiles(String expectedKey) throws IOException {
    try (var paths = Files.list(temp)) {
      assertThat(paths.map(path -> path.getFileName().toString()).toList()).containsExactly(expectedKey);
    }
  }

  private MockMultipartFile file(String filename, String contentType, String content) {
    return new MockMultipartFile("files", filename, contentType, content.getBytes(StandardCharsets.UTF_8));
  }

  private record Fixture(JdbcClient jdbc, UUID user, UUID space, ImportService imports, LocalObjectStore objects) {}

  private static class ErrorAfterPutStore extends LocalObjectStore {
    private int remainingPuts;

    ErrorAfterPutStore(Path root) {
      super(root.toString());
    }

    @Override
    public void put(String key, InputStream content, long length) throws IOException {
      super.put(key, content, length);
      if (--remainingPuts == 0) {
        throw new IOException("test failure after writing object");
      }
    }
  }
}
