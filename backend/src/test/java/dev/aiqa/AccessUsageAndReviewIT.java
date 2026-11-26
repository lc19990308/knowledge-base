package dev.aiqa;

import static dev.aiqa.testsupport.ApiResponseAssertions.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.aiqa.testsupport.TestDatabase;
import dev.aiqa.worker.DocumentParseWorker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

/** Rejection, permission isolation, CSRF, idempotent replay and user-scoped usage. */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessUsageAndReviewIT {

  private static final TestDatabase DATABASE = TestDatabase.fromEnvironment().orElse(null);
  private static final String ADMIN = "acc-admin";
  private static final String READER = "acc-reader";
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
    registry.add("aiqa.model.provider", () -> "fake");
    registry.add("aiqa.bootstrap.enabled", () -> "false");
  }

  private static Path tempRoot() {
    try {
      return Files.createTempDirectory("aiqa-objects2-");
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private DocumentParseWorker parseWorker;

  @BeforeAll
  void requireDatabase() throws Exception {
    Assumptions.assumeTrue(DATABASE != null, "未配置 IT_DB_* 测试数据库，跳过 PostgreSQL 集成测试");
    callAs(ADMIN, get("/api/v1/auth/me")).andExpect(status().isOk());
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

  private ResultActions callAs(String subject, MockHttpServletRequestBuilder builder) throws Exception {
    return mvc.perform(builder.header("X-Dev-Subject", subject).with(csrf()));
  }

  private ResultActions uploadAs(String subject, MockMultipartHttpServletRequestBuilder builder) throws Exception {
    return mvc.perform(builder.header("X-Dev-Subject", subject).with(csrf()));
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private static MockMultipartFile markdown(String name, String body) {
    return new MockMultipartFile("files", name, "text/markdown", body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void aggregateUsageUsesQuestionDateAndDistinguishesZeroFromMissing() throws Exception {
    String reader = "usage-boundary-reader";
    callAs(reader, get("/api/v1/auth/me")).andExpect(status().isOk());
    var userId = jdbc.sql("SELECT id FROM app_user WHERE subject = :subject")
        .param("subject", reader).query(java.util.UUID.class).single();
    var assistantId = java.util.UUID.randomUUID();
    var conversationId = java.util.UUID.randomUUID();
    var questionId = java.util.UUID.randomUUID();
    jdbc.sql("INSERT INTO assistant (id, name) VALUES (:id, '用量边界测试助手')")
        .param("id", assistantId).update();
    jdbc.sql("INSERT INTO conversation (id, owner_id, assistant_id) VALUES (:id, :user, :assistant)")
        .param("id", conversationId).param("user", userId).param("assistant", assistantId).update();
    jdbc.sql("""
        INSERT INTO question (id, conversation_id, user_id, request_key, text, status, accepted_at)
        VALUES (:id, :conversation, :user, 'usage-boundary-question', '跨日用量', 'SUCCEEDED',
                TIMESTAMPTZ '2020-01-01 23:59:59+08')
        """).param("id", questionId).param("conversation", conversationId).param("user", userId).update();
    jdbc.sql("""
        INSERT INTO model_call_usage (call_id, question_id, user_id, input_tokens, output_tokens, received_at)
        VALUES ('usage-boundary-zero', :question, :user, 0, 0, TIMESTAMPTZ '2020-01-02 00:00:01+08'),
               ('usage-boundary-missing', :question, :user, NULL, NULL, TIMESTAMPTZ '2020-01-02 00:00:02+08')
        """).param("question", questionId).param("user", userId).update();

    String date = "2020-01-01";
    callAs(ADMIN, get("/api/v1/usage").param("period", "DAY").param("date", date))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.questionCount").value(0));
    callAs(ADMIN, get("/api/v1/usage").param("period", "DAY").param("date", date).param("allUsers", "true"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.data.questionCount").value(1)).andExpect(jsonPath("$.data.reportedTokenCount").value(0))
        .andExpect(jsonPath("$.data.unreportedModelCallCount").value(1));
    callAs(ADMIN, get("/api/v1/usage/series").param("period", "DAY").param("date", date).param("allUsers", "true"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.buckets[23].questionCount").value(1));
    callAs(ADMIN, get("/api/v1/usage/questions").param("period", "DAY").param("date", date).param("allUsers", "true"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].userId").value(userId.toString()))
        .andExpect(jsonPath("$.data.items[0].tokenTotal").value(0));
    callAs(ADMIN, get("/api/v1/usage/ranking").param("period", "DAY").param("date", date))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].reportedTokenCount").value(0))
        .andExpect(jsonPath("$.data.items[0].unreportedModelCallCount").value(1));
    callAs(ADMIN, get("/api/v1/usage").param("period", "DAY").param("date", "2020-01-02").param("allUsers", "true"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.reportedTokenCount").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.data.unreportedModelCallCount").value(0));
    for (String path : List.of("/api/v1/usage", "/api/v1/usage/series", "/api/v1/usage/questions")) {
      callAs(reader, get(path).param("period", "DAY").param("date", date).param("allUsers", "true"))
          .andExpect(status().isForbidden());
      callAs(ADMIN, get(path).param("period", "DAY").param("date", date).param("allUsers", "true")
          .param("userId", userId.toString())).andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
  }

  @Test
  void reviewPermissionsIdempotencyAndUsageFollowTheDesign() throws Exception {
    String spaceId =
        JsonPath.read(
            body(
                callAs(ADMIN, post("/api/v1/spaces")
                        .contentType("application/json")
                        .content("{\"name\":\"权限与审核测试库\"}"))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.spaceId");

    // Import twice with the same key: the replay must return the same batch and not duplicate rows.
    MockMultipartFile file = markdown("制度.md", "制度规定了十五个工作日的报销时限。");
    String firstBatch =
        JsonPath.read(
            body(
                uploadAs(ADMIN, multipart("/api/v1/spaces/{spaceId}/imports", spaceId)
                        .file(file)
                        .header("Idempotency-Key", "acc-import-1"))
                    .andExpect(status().isAccepted())
                    .andReturn()),
            "$.data.batchId");
    String replayBatch =
        JsonPath.read(
            body(
                uploadAs(ADMIN, multipart("/api/v1/spaces/{spaceId}/imports", spaceId)
                        .file(file)
                        .header("Idempotency-Key", "acc-import-1"))
                    .andExpect(status().isAccepted())
                    .andReturn()),
            "$.data.batchId");
    assertThat(replayBatch).isEqualTo(firstBatch);
    assertThat(
            jdbc.sql("SELECT count(*) FROM document_version WHERE space_id = :space")
                .param("space", java.util.UUID.fromString(spaceId))
                .query(Long.class)
                .single())
        .isEqualTo(1);
    parseWorker.poll();

    String docId =
        jdbc.sql("SELECT id FROM document_version WHERE space_id = :space")
            .param("space", java.util.UUID.fromString(spaceId))
            .query(java.util.UUID.class)
            .single()
            .toString();
    String draftEtag =
        callAs(ADMIN, get("/api/v1/spaces/{spaceId}/draft", spaceId)).andReturn().getResponse().getHeader("ETag");
    String savedEtag =
        callAs(ADMIN, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/spaces/{spaceId}/draft", spaceId)
                .header("If-Match", draftEtag)
                .contentType("application/json")
                .content("{\"entries\":[{\"documentVersionId\":\"%s\",\"policyState\":\"EFFECTIVE\"}]}"
                    .formatted(docId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("ETag");

    String requestId =
        JsonPath.read(
            body(
                callAs(ADMIN, post("/api/v1/spaces/{spaceId}/publish-requests", spaceId)
                        .header("If-Match", savedEtag)
                        .header("Idempotency-Key", "acc-submit-1")
                        .contentType("application/json")
                        .content("{\"reason\":\"首次提交\"}"))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.requestId");

    // Missing If-Match and missing reason handling.
    callAs(ADMIN, post("/api/v1/publish-requests/{requestId}/decision", requestId)
            .header("Idempotency-Key", "acc-decide-0")
            .contentType("application/json")
            .content("{\"decision\":\"REJECT\"}"))
        .andExpect(status().isPreconditionRequired())
        .andExpect(problem("IF_MATCH_REQUIRED"));
    String requestEtag =
        callAs(ADMIN, get("/api/v1/publish-requests/{requestId}", requestId))
            .andReturn()
            .getResponse()
            .getHeader("ETag");
    callAs(ADMIN, post("/api/v1/publish-requests/{requestId}/decision", requestId)
            .header("If-Match", requestEtag)
            .header("Idempotency-Key", "acc-decide-1")
            .contentType("application/json")
            .content("{\"decision\":\"REJECT\"}"))
        .andExpect(status().isUnprocessableEntity());
    MvcResult rejected =
        callAs(ADMIN, post("/api/v1/publish-requests/{requestId}/decision", requestId)
                .header("If-Match", requestEtag)
                .header("Idempotency-Key", "acc-decide-2")
                .contentType("application/json")
                .content("{\"decision\":\"REJECT\",\"reason\":\"缺少生效日期\"}"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(JsonPath.<String>read(body(rejected), "$.data.status")).isEqualTo("REJECTED");

    // A decided request cannot be approved later even with its current ETag.
    String rejectedEtag = rejected.getResponse().getHeader("ETag");
    callAs(ADMIN, post("/api/v1/publish-requests/{requestId}/decision", requestId)
            .header("If-Match", rejectedEtag)
            .header("Idempotency-Key", "acc-decide-3")
            .contentType("application/json")
            .content("{\"decision\":\"APPROVE\"}"))
        .andExpect(status().isConflict());

    // CSRF is enforced for writes.
    mvc.perform(post("/api/v1/spaces")
            .header("X-Dev-Subject", ADMIN)
            .contentType("application/json")
            .content("{\"name\":\"无 CSRF\"}"))
        .andExpect(status().isForbidden())
        .andExpect(problem("CSRF_INVALID"));

    // Reading a space the user is not a member of is a uniform 404.
    callAs(READER, get("/api/v1/spaces/{spaceId}/search", spaceId).param("q", "十五个工作日"))
        .andExpect(status().isNotFound());
    callAs(READER, get("/api/v1/spaces/{spaceId}/documents/{documentId}", spaceId, docId))
        .andExpect(status().isNotFound());
    callAs(READER, get("/api/v1/spaces/{spaceId}", spaceId)).andExpect(status().isNotFound());

    // Usage is user-scoped and reports a Beijing natural day.
    String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
    MvcResult usage =
        callAs(ADMIN, get("/api/v1/usage").param("period", "DAY").param("date", today))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(JsonPath.<String>read(body(usage), "$.data.timeZone")).isEqualTo("Asia/Shanghai");
    assertThat(JsonPath.<Number>read(body(usage), "$.data.questionCount")).isNotNull();

    // The trend endpoint must bucket the same Beijing window.
    MvcResult series =
        callAs(
                ADMIN,
                get("/api/v1/usage/series").param("period", "DAY").param("date", today))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(JsonPath.<String>read(body(series), "$.data.timeZone")).isEqualTo("Asia/Shanghai");
    assertThat(JsonPath.<List<String>>read(body(series), "$.data.buckets[*].label")).hasSize(24);
    assertThat(JsonPath.<String>read(body(series), "$.data.buckets[0].label")).matches("\\d{2}:00");
    callAs(READER, get("/api/v1/usage/series").param("period", "DAY").param("date", today)
            .param("userId", "00000000-0000-0000-0000-000000000000"))
        .andExpect(status().isForbidden());

    // 文档类指标按空间统计，仅管理员可见。
    callAs(ADMIN, get("/api/v1/usage/spaces").param("period", "DAY").param("date", today))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isArray());
    callAs(READER, get("/api/v1/usage/spaces").param("period", "DAY").param("date", today))
        .andExpect(status().isForbidden());

    // 公开接口：未配置 IdP 的上下文中登录方式为空数组。
    mvc.perform(get("/api/v1/auth/providers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isArray());
    mvc.perform(get("/api/v1/usage").param("period", "DAY").param("date", today))
        .andExpect(status().isUnauthorized());

    // Assistant binding list hides spaces the reader cannot read.
    String assistantId =
        JsonPath.read(
            body(
                callAs(ADMIN, post("/api/v1/assistants")
                        .header("Idempotency-Key", "acc-assistant-1")
                        .contentType("application/json")
                        .content("{\"name\":\"权限助手\",\"bindings\":[{\"spaceId\":\"%s\",\"role\":\"RETRIEVAL\"}],\"templateId\":null}"
                            .formatted(spaceId)))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.assistantId");
    assertThat(assistantId).isNotBlank();
    MvcResult visibleToReader =
        callAs(READER, get("/api/v1/assistants")).andExpect(status().isOk()).andReturn();
    assertThat(JsonPath.<List<String>>read(body(visibleToReader), "$.data.items[*].assistantId"))
        .doesNotContain(assistantId);

    // Operational metrics are admin-only; a reader must not see them.
    callAs(ADMIN, get("/actuator/metrics")).andExpect(status().isOk());
    callAs(READER, get("/actuator/metrics")).andExpect(status().isForbidden());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());

    callAs(READER, get("/api/v1/spaces/{spaceId}/members", spaceId))
        .andExpect(status().isForbidden());

    // Administrative user management: find, grant, disable, and protect the last admin.
    String readerId =
        JsonPath.read(
            body(callAs(ADMIN, get("/api/v1/users").param("q", READER)).andExpect(status().isOk()).andReturn()),
            "$.data.items[0].userId");
    callAs(ADMIN, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .put("/api/v1/users/{userId}/access", readerId)
            .contentType("application/json")
            .content("{\"enabled\":true,\"roles\":[\"USER\"],\"canCreateSpace\":true}"))
        .andExpect(status().isOk());
    callAs(READER, post("/api/v1/spaces")
            .contentType("application/json")
            .content("{\"name\":\"读者自建空间\"}"))
        .andExpect(status().isCreated());
    callAs(ADMIN, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .put("/api/v1/users/{userId}/access", readerId)
            .contentType("application/json")
            .content("{\"enabled\":false,\"roles\":[\"USER\"],\"canCreateSpace\":false}"))
        .andExpect(status().isOk());
    callAs(READER, get("/api/v1/auth/me")).andExpect(status().isForbidden());

    String adminId =
        JsonPath.read(
            body(callAs(ADMIN, get("/api/v1/users").param("q", ADMIN)).andReturn()),
            "$.data.items[0].userId");
    callAs(ADMIN, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .put("/api/v1/users/{userId}/access", adminId)
            .contentType("application/json")
            .content("{\"enabled\":true,\"roles\":[\"USER\"],\"canCreateSpace\":false}"))
        .andExpect(status().isConflict());

    // Membership changes are audited.
    callAs(ADMIN, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .put("/api/v1/spaces/{spaceId}/members/{userId}", spaceId, readerId)
            .contentType("application/json")
            .content("{\"canRead\":true,\"canMaintain\":false}"))
        .andExpect(status().isOk());
    assertThat(
            jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'SPACE_MEMBER_UPDATED'")
                .query(Long.class)
                .single())
        .isGreaterThanOrEqualTo(1);

    callAs(ADMIN, get("/api/v1/spaces/{spaceId}/members", spaceId))
        .andExpect(status().isOk());
    callAs(ADMIN, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .put("/api/v1/spaces/{spaceId}/members/{userId}", spaceId, readerId)
            .contentType("application/json")
            .content("{\"canRead\":false,\"canMaintain\":false}"))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .jsonPath("$.data.canRead").value(false));
    assertThat(jdbc.sql("SELECT count(*) FROM space_member WHERE space_id = :space AND user_id = :user")
        .param("space", java.util.UUID.fromString(spaceId))
        .param("user", java.util.UUID.fromString(readerId))
        .query(Long.class).single()).isZero();

    // A rejected request leaves no publication pointer behind.
    assertThat(
            jdbc.sql("SELECT current_publication_id FROM space WHERE id = :space")
                .param("space", java.util.UUID.fromString(spaceId))
                .query(java.util.UUID.class)
                .optional())
        .isEmpty();
  }
}
