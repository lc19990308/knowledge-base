package dev.aiqa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.aiqa.testsupport.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** File-scan rejection, per-user question limits and token accounting. */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityAndLimitsIT {

  private static final TestDatabase DATABASE = TestDatabase.fromEnvironment().orElse(null);
  private static final String ADMIN = "lim-admin";
  private static final Path OBJECT_ROOT = tempRoot();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    if (DATABASE == null) {
      return;
    }
    registry.add("spring.datasource.url", DATABASE::jdbcUrl);
    registry.add("spring.datasource.username", DATABASE::user);
    registry.add("spring.datasource.password", DATABASE::password);
    registry.add("spring.flyway.schemas", DATABASE::schema);
    registry.add("spring.flyway.default-schema", DATABASE::schema);
    registry.add("spring.flyway.create-schemas", () -> "true");
    registry.add("aiqa.security.dev-header-auth.enabled", () -> "true");
    registry.add("aiqa.worker.enabled", () -> "false");
    registry.add("aiqa.object-store.local-root", () -> OBJECT_ROOT.toString());
    registry.add("aiqa.rate-limit.writes-per-minute", () -> "50");
    registry.add("aiqa.rate-limit.questions-per-minute", () -> "2");
    registry.add("aiqa.rate-limit.reads.status-per-minute", () -> "1");
    registry.add("aiqa.worker.capacity.answer", () -> "2");
    registry.add("aiqa.worker.concurrency.answer", () -> "1");
  }

  private static Path tempRoot() {
    try {
      return Files.createTempDirectory("aiqa-objects3-");
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private dev.aiqa.persistence.JobRepository jobs;
  @Autowired private io.micrometer.core.instrument.MeterRegistry meters;
  @Autowired private dev.aiqa.worker.DocumentParseWorker parseWorker;
  @Autowired private dev.aiqa.worker.MaintenanceWorker maintenance;

  @BeforeAll
  void requireDatabase() throws Exception {
    Assumptions.assumeTrue(DATABASE != null, "未配置 IT_DB_* 测试数据库，跳过 PostgreSQL 集成测试");
    call(get("/api/v1/auth/me")).andExpect(status().isOk());
    jdbc.sql(
            """
            UPDATE user_role SET role = 'SYSTEM_ADMIN'
            WHERE user_id = (SELECT id FROM app_user WHERE subject = :subject)
            """)
        .param("subject", ADMIN)
        .update();
  }

  @AfterAll
  void cleanUp() {
    if (DATABASE != null) {
      DATABASE.close();
    }
  }

  private ResultActions call(MockHttpServletRequestBuilder builder) throws Exception {
    return mvc.perform(builder.header("X-Dev-Subject", ADMIN).with(csrf()));
  }

  private ResultActions upload(MockMultipartHttpServletRequestBuilder builder) throws Exception {
    return mvc.perform(builder.header("X-Dev-Subject", ADMIN).with(csrf()));
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void rejectsSpoofedFilesLimitsQuestionsAndAccountsTokens() throws Exception {
    String spaceId =
        JsonPath.read(
            body(
                call(post("/api/v1/spaces")
                        .contentType("application/json")
                        .content("{\"name\":\"限制与扫描测试库\"}"))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.spaceId");

    MockMultipartFile spoofed =
        new MockMultipartFile("files", "伪造文档.pdf", "application/pdf",
            "这不是 PDF 内容".getBytes(StandardCharsets.UTF_8));
    MockMultipartFile genuine =
        new MockMultipartFile("files", "真实制度.md", "text/markdown",
            "制度正文：十五个工作日。".getBytes(StandardCharsets.UTF_8));
    MvcResult imported =
        upload(multipart("/api/v1/spaces/{spaceId}/imports", spaceId)
                .file(spoofed)
                .file(genuine)
                .header("Idempotency-Key", "lim-import-1"))
            .andExpect(status().isAccepted())
            .andReturn();
    String importJson = body(imported);
    assertThat(JsonPath.<String>read(importJson, "$.data.files[0].status")).isEqualTo("REJECTED");
    assertThat(JsonPath.<String>read(importJson, "$.data.files[0].code")).isEqualTo("SUSPICIOUS_FILE");
    assertThat(JsonPath.<String>read(importJson, "$.data.files[1].status")).isEqualTo("QUEUED");
    assertThat(
            jdbc.sql("SELECT count(*) FROM document_version WHERE space_id = :space")
                .param("space", UUID.fromString(spaceId))
                .query(Long.class)
                .single())
        .isEqualTo(1);

    parseWorker.poll();
    MvcResult reindexed =
        call(post("/api/v1/spaces/{spaceId}/reindex", spaceId)
                .header("Idempotency-Key", "lim-reindex-1"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(JsonPath.<Number>read(body(reindexed), "$.data.enqueued").intValue()).isEqualTo(1);

    String assistantId =
        JsonPath.read(
            body(
                call(post("/api/v1/assistants")
                        .header("Idempotency-Key", "lim-assistant-1")
                        .contentType("application/json")
                        .content("{\"name\":\"限额助手\",\"bindings\":[{\"spaceId\":\"%s\",\"role\":\"RETRIEVAL\"}],\"templateId\":null}"
                            .formatted(spaceId)))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.assistantId");
    String input = "{\"assistantId\":\"%s\"}".formatted(assistantId);
    call(post("/api/v1/conversations").contentType("application/json").content(input))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    MvcResult created = call(post("/api/v1/conversations")
            .header("Idempotency-Key", "lim-conversation-1").contentType("application/json").content(input))
        .andExpect(status().isCreated()).andReturn();
    MvcResult replayed = call(post("/api/v1/conversations")
            .header("Idempotency-Key", "lim-conversation-1").contentType("application/json").content(input))
        .andExpect(status().isCreated()).andReturn();
    assertThat(JsonPath.<Object>read(body(replayed), "$.data"))
        .isEqualTo(JsonPath.<Object>read(body(created), "$.data"));
    assertThat(replayed.getResponse().getHeader("Location"))
        .isEqualTo(created.getResponse().getHeader("Location"));
    String conversationId = JsonPath.read(body(created), "$.data.conversationId");
    call(post("/api/v1/conversations").header("Idempotency-Key", "lim-conversation-1")
            .contentType("application/json").content("{\"assistantId\":\"%s\",\"title\":\"另一个标题\"}".formatted(assistantId)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    assertThat(jdbc.sql("SELECT count(*) FROM conversation").query(Long.class).single()).isEqualTo(1L);

    String questionPath = "/api/v1/conversations/{conversationId}/questions";
    String original = "{\"text\":\"限额测试第一次\"}";
    MvcResult accepted = call(post(questionPath, conversationId).header("Idempotency-Key", "lim-question-0")
            .contentType("application/json").content(original))
        .andExpect(status().isAccepted()).andReturn();
    for (int index = 0; index < 3; index++) {
      MvcResult replay = call(post(questionPath, conversationId).header("Idempotency-Key", "lim-question-0")
              .contentType("application/json").content(original))
          .andExpect(status().isAccepted()).andReturn();
      assertThat(JsonPath.<Object>read(body(replay), "$.data"))
          .isEqualTo(JsonPath.<Object>read(body(accepted), "$.data"));
    }
    call(post(questionPath, conversationId).header("Idempotency-Key", "lim-question-1")
            .contentType("application/json").content("{\"text\":\"限额测试第二次\"}"))
        .andExpect(status().isAccepted());
    jdbc.sql("UPDATE job SET status = 'FAILED' WHERE question_id IN (SELECT id FROM question WHERE request_key = 'lim-question-1')")
        .update();
    call(post(questionPath, conversationId).header("Idempotency-Key", "lim-question-2")
            .contentType("application/json").content("{\"text\":\"限额测试第三次\"}"))
        .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    call(post(questionPath, conversationId).header("Idempotency-Key", "lim-question-0")
            .contentType("application/json").content(original)).andExpect(status().isAccepted());
    assertThat(jdbc.sql("SELECT count(*) FROM question").query(Long.class).single()).isEqualTo(2L);
    assertThat(jdbc.sql("SELECT count(*) FROM job WHERE job_type = 'ANSWER_QUESTION'").query(Long.class).single())
        .isEqualTo(2L);
    assertThat(jdbc.sql("SELECT count(*) FROM request_idempotency WHERE idempotency_key = 'lim-question-2'")
        .query(Long.class).single()).isZero();

    String questionLocation = accepted.getResponse().getHeader("Location");
    call(get(questionLocation)).andExpect(status().isOk());
    call(get(questionLocation)).andExpect(status().isOk());
    assertThat(meters.get("aiqa.rate.limit.requests").tags("operation", "status", "outcome", "would_reject")
        .counter().count()).isGreaterThanOrEqualTo(1);

    // Token accounting: one reported call plus one unreported call.
    UUID userId =
        jdbc.sql("SELECT id FROM app_user WHERE subject = :subject")
            .param("subject", ADMIN)
            .query(UUID.class)
            .single();
    UUID questionId =
        jdbc.sql("SELECT id FROM question WHERE user_id = :user ORDER BY accepted_at LIMIT 1")
            .param("user", userId)
            .query(UUID.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO model_call_usage (call_id, question_id, user_id, input_tokens, output_tokens)
            VALUES ('lim-call-1', :question, :user, 100, 50), ('lim-call-2', :question, :user, NULL, NULL)
            """)
        .param("question", questionId)
        .param("user", userId)
        .update();

    String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
    String usage =
        body(call(get("/api/v1/usage").param("period", "DAY").param("date", today))
            .andExpect(status().isOk())
            .andReturn());
    assertThat(JsonPath.<Number>read(usage, "$.data.reportedTokenCount").longValue()).isEqualTo(150);
    assertThat(JsonPath.<Number>read(usage, "$.data.unreportedModelCallCount").longValue()).isEqualTo(1);

    jdbc.sql("UPDATE job SET status = 'QUEUED' WHERE question_id IN (SELECT id FROM question WHERE request_key = 'lim-question-1')")
        .update();
    var answerKind = dev.aiqa.persistence.JobRepository.Kind.ANSWER;
    var claimed = jobs.claimNext(answerKind, 180);
    assertThat(claimed).isNotNull();
    assertThat(jobs.claimNext(answerKind, 180)).isNull();
    call(post(questionPath, conversationId).header("Idempotency-Key", "lim-question-capacity")
            .contentType("application/json").content("{\"text\":\"验证任务容量\"}"))
        .andExpect(status().isServiceUnavailable()).andExpect(header().exists("Retry-After"));
    assertThat(jdbc.sql("SELECT count(*) FROM request_idempotency WHERE idempotency_key = 'lim-question-capacity'")
        .query(Long.class).single()).isZero();
    jdbc.sql("UPDATE job SET lease_expires_at = now() - interval '1 second' WHERE id = :id")
        .param("id", claimed.id()).update();
    maintenance.run();
    assertThat(jdbc.sql("SELECT status FROM job WHERE id = :id").param("id", claimed.id())
        .query(String.class).single()).isEqualTo("FAILED");
    assertThat(jdbc.sql("SELECT failure_code FROM question WHERE id = :id").param("id", claimed.questionId())
        .query(String.class).single()).isEqualTo("MODEL_UNAVAILABLE");
    assertThat(jobs.claimNext(answerKind, 180).id()).isNotEqualTo(claimed.id());

    // Maintenance removes expired idempotency rows.
    jdbc.sql(
            """
            INSERT INTO request_idempotency
              (user_id, operation, idempotency_key, request_digest, status, created_at, expires_at)
            VALUES (:user, 'EXPIRED_TEST', 'expired-key', repeat('a', 64), 'IN_PROGRESS',
                    now() - interval '2 hours', now() - interval '1 hour')
            """)
        .param("user", userId)
        .update();
    maintenance.run();
    assertThat(
            jdbc.sql("SELECT count(*) FROM request_idempotency WHERE operation = 'EXPIRED_TEST'")
                .query(Long.class)
                .single())
        .isZero();
  }
}
