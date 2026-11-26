package dev.aiqa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiqa.testsupport.TestDatabase;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SchemaMigrationTest {

  @Test
  void appliesEveryMigrationAndCreatesPublishAndEvidenceTables() {
    Optional<TestDatabase> database = TestDatabase.fromEnvironment();
    Assumptions.assumeTrue(database.isPresent(), "未配置 IT_DB_* 测试数据库，跳过 PostgreSQL 集成测试");
    try (TestDatabase testDatabase = database.orElseThrow()) {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(testDatabase.jdbcUrl(), testDatabase.user(), testDatabase.password());
      Flyway flyway =
          Flyway.configure()
              .dataSource(dataSource)
              .schemas(testDatabase.schema())
              .defaultSchema(testDatabase.schema())
              .load();
      assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(2);

      JdbcClient jdbc = JdbcClient.create(dataSource);
      Long tables =
          jdbc.sql(
                  """
                  SELECT count(*) FROM information_schema.tables
                  WHERE table_schema = :schema
                    AND table_name IN ('space', 'space_snapshot_document', 'publish_request',
                                       'space_publication', 'paragraph_evidence', 'citation', 'job',
                                       'cleaning_batch', 'cleaning_source_file', 'cleaning_item')
                  """)
              .param("schema", testDatabase.schema())
              .query(Long.class)
              .single();
      assertThat(tables).isEqualTo(10);

      String openIndex =
          jdbc.sql(
                  """
                  SELECT indexdef FROM pg_indexes
                  WHERE schemaname = :schema AND indexname = 'uq_publish_request_open_space'
                  """)
              .param("schema", testDatabase.schema())
              .query(String.class)
              .optional()
              .orElseThrow();
      assertThat(openIndex).contains("WHERE").contains("PENDING_REVIEW");
    }
  }
}
