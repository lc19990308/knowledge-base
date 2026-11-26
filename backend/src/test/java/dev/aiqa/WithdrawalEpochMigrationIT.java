package dev.aiqa;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiqa.conversation.QuestionRepository;
import dev.aiqa.publish.PublishRepository;
import dev.aiqa.testsupport.TestDatabase;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WithdrawalEpochMigrationIT {

  @Test
  void backfillsWithdrawalsWithAndWithoutAnAlreadyRepublishedSnapshot() {
    var configured = TestDatabase.fromEnvironment();
    Assumptions.assumeTrue(configured.isPresent(), "未配置 IT_DB_* 测试数据库");
    try (TestDatabase database = configured.orElseThrow()) {
      Flyway.configure().dataSource(database.jdbcUrl(), database.user(), database.password())
          .defaultSchema(database.schema()).target("11").load().migrate();
      var jdbc = JdbcClient.create(new DriverManagerDataSource(
          database.jdbcUrl(), database.user(), database.password()));
      var publishes = new PublishRepository(jdbc);
      var questions = new QuestionRepository(jdbc);
      UUID user = UUID.randomUUID();
      jdbc.sql("INSERT INTO app_user (id, issuer, subject, display_name) VALUES (:id, 'it', 'legacy', 'legacy')")
          .param("id", user).update();
      UUID assistant = UUID.randomUUID();
      jdbc.sql("INSERT INTO assistant (id, name) VALUES (:id, 'legacy')").param("id", assistant).update();
      UUID conversation = UUID.randomUUID();
      jdbc.sql("INSERT INTO conversation (id, owner_id, assistant_id) VALUES (:id, :user, :assistant)")
          .param("id", conversation).param("user", user).param("assistant", assistant).update();

      UUID neverWithdrawn = createSpace(jdbc, user);
      UUID republished = createSpace(jdbc, user);
      UUID stillWithdrawn = createSpace(jdbc, user);
      UUID validParagraph = legacyHistory(jdbc, publishes, questions, user, conversation, neverWithdrawn, false, true);
      UUID revokedParagraph = legacyHistory(jdbc, publishes, questions, user, conversation, republished, true, true);
      UUID withdrawnParagraph = legacyHistory(jdbc, publishes, questions, user, conversation, stillWithdrawn, true, false);

      Flyway.configure().dataSource(database.jdbcUrl(), database.user(), database.password())
          .defaultSchema(database.schema()).load().migrate();
      assertThat(questions.paragraphStillAuthorized(validParagraph, user)).isTrue();
      assertThat(questions.paragraphStillAuthorized(revokedParagraph, user)).isFalse();
      assertThat(questions.paragraphStillAuthorized(withdrawnParagraph, user)).isFalse();
      assertThat(jdbc.sql("SELECT last_withdrawal_epoch FROM space WHERE id = :id")
          .param("id", stillWithdrawn).query(Long.class).single()).isEqualTo(2L);
    }
  }

  private static UUID createSpace(JdbcClient jdbc, UUID user) {
    UUID space = UUID.randomUUID();
    jdbc.sql("INSERT INTO space (id, owner_id, name) VALUES (:id, :user, 'legacy')")
        .param("id", space).param("user", user).update();
    jdbc.sql("INSERT INTO space_member (space_id, user_id, can_read, granted_by) VALUES (:space, :user, true, :user)")
        .param("space", space).param("user", user).update();
    return space;
  }

  private static UUID legacyHistory(
      JdbcClient jdbc, PublishRepository publishes, QuestionRepository questions,
      UUID user, UUID conversation, UUID space, boolean withdraw, boolean publishAgain) {
    UUID doc = UUID.randomUUID();
    jdbc.sql("""
            INSERT INTO document_version (id, space_id, normalized_filename, display_filename, object_key,
              sha256, media_type, size_bytes, scan_state, parse_state, created_by)
            VALUES (:doc, :space, 'legacy.md', 'legacy.md', :key,
              repeat('0', 64), 'text/markdown', 1, 'CLEAN', 'READY', :user)
            """)
        .param("doc", doc).param("space", space).param("key", doc.toString()).param("user", user).update();
    UUID firstPublication = publish(jdbc, publishes, space, doc, user);
    UUID question = UUID.randomUUID();
    jdbc.sql("""
            INSERT INTO question (id, conversation_id, user_id, request_key, text, status)
            VALUES (:id, :conv, :user, :key, 'legacy', 'SUCCEEDED')
            """)
        .param("id", question).param("conv", conversation).param("user", user)
        .param("key", question.toString()).update();
    UUID paragraph = questions.insertParagraph(question, 0, "legacy", "ANSWER");
    String call = question.toString();
    questions.recordUsage(call, question, user, 1L, 1L);
    questions.insertEvidence(paragraph, call, 0, space, firstPublication, 1L, doc, "legacy", null);
    questions.insertCitation(paragraph, call, 0, "legacy.md");
    if (withdraw) {
      publishes.withdraw(space, firstPublication, user, "legacy withdrawal");
    }
    if (publishAgain) {
      publish(jdbc, publishes, space, doc, user);
    }
    return paragraph;
  }

  private static UUID publish(JdbcClient jdbc, PublishRepository publishes, UUID space, UUID doc, UUID user) {
    UUID snapshot = publishes.createSnapshot(space, 0, "0".repeat(64), user);
    jdbc.sql("""
            INSERT INTO space_snapshot_document (space_id, snapshot_id, document_version_id, policy_state)
            VALUES (:space, :snapshot, :doc, 'EFFECTIVE')
            """)
        .param("space", space).param("snapshot", snapshot).param("doc", doc).update();
    long epoch = publishes.epoch(space);
    UUID request = publishes.createRequest(space, snapshot, publishes.currentPublication(space).orElse(null),
        epoch, user, "legacy publication");
    return publishes.publish(space, request, user, null, epoch);
  }
}
