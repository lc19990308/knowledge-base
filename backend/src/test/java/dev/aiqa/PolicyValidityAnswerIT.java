package dev.aiqa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.aiqa.testsupport.TestDatabase;
import dev.aiqa.worker.AnswerWorker;
import dev.aiqa.worker.DocumentParseWorker;
import dev.aiqa.worker.PublishWorker;
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
class PolicyValidityAnswerIT {

  private static final TestDatabase DATABASE = TestDatabase.fromEnvironment().orElse(null);
  private static final String ADMIN = "pol-admin";
  private static final String READER = "pol-reader";
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
  }

  private static Path tempRoot() {
    try {
      return Files.createTempDirectory("aiqa-objects-pol-");
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private DocumentParseWorker parseWorker;
  @Autowired private PublishWorker publishWorker;
  @Autowired private AnswerWorker answerWorker;

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
  void expiredAndUnknownPoliciesProduceTheirOwnParagraphKinds() throws Exception {
    String spaceId =
        JsonPath.read(
            body(
                callAs(ADMIN, post("/api/v1/spaces")
                        .contentType("application/json")
                        .content("{\"name\":\"政策有效性测试库\",\"department\":\"hr\"}"))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.spaceId");

    String docId =
        JsonPath.read(
            body(
                uploadAs(ADMIN, multipart("/api/v1/spaces/{spaceId}/imports", spaceId)
                        .file(markdown("差旅制度.md", "差旅报销须在出差结束后十五个工作日内提交。"))
                        .header("Idempotency-Key", "pol-import-1"))
                    .andExpect(status().isAccepted())
                    .andReturn()),
            "$.data.files[0].documentVersionId");
    parseWorker.poll();

    // 明确失效：有效期末日早于今天。
    String expiredEtag =
        callAs(ADMIN, get("/api/v1/spaces/" + spaceId + "/draft")).andReturn()
            .getResponse().getHeader("ETag");
    String savedEtag =
        mvc.perform(
                put("/api/v1/spaces/{spaceId}/draft", spaceId)
                    .header("X-Dev-Subject", ADMIN)
                    .header("If-Match", expiredEtag)
                    .with(csrf())
                    .contentType("application/json")
                    .content(("{\"entries\":[{\"documentVersionId\":\"%s\",\"policyState\":\"EFFECTIVE\","
                        + "\"effectiveFrom\":\"2020-01-01\",\"effectiveUntil\":\"2020-12-31\"}]}")
                        .formatted(docId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getHeader("ETag");

    String requestId =
        JsonPath.read(
            body(
                mvc.perform(
                        post("/api/v1/spaces/{spaceId}/publish-requests", spaceId)
                            .header("X-Dev-Subject", ADMIN)
                            .header("If-Match", savedEtag)
                            .header("Idempotency-Key", "pol-submit-1")
                            .with(csrf())
                            .contentType("application/json")
                            .content("{\"reason\":\"过期政策\"}"))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.requestId");
    String requestEtag =
        callAs(ADMIN, get("/api/v1/publish-requests/" + requestId)).andReturn()
            .getResponse().getHeader("ETag");
    mvc.perform(
            post("/api/v1/publish-requests/{requestId}/decision", requestId)
                .header("X-Dev-Subject", ADMIN)
                .header("If-Match", requestEtag)
                .header("Idempotency-Key", "pol-decide-1")
                .with(csrf())
                .contentType("application/json")
                .content("{\"decision\":\"APPROVE\"}"))
        .andExpect(status().isOk());
    parseWorker.poll();
    publishWorker.poll();

    String assistantId =
        JsonPath.read(
            body(
                mvc.perform(
                        post("/api/v1/assistants")
                            .header("X-Dev-Subject", ADMIN)
                            .header("Idempotency-Key", "pol-assistant-1")
                            .with(csrf())
                            .contentType("application/json")
                            .content(("{\"name\":\"政策助手\",\"bindings\":[{\"spaceId\":\"%s\","
                                + "\"role\":\"RETRIEVAL\"}],\"templateId\":null}")
                                .formatted(spaceId)))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.assistantId");

    // 检索结果必须带出有效性，界面据此显示「已失效 / 待核实」。
    String search =
        body(callAs(ADMIN, get("/api/v1/spaces/" + spaceId + "/search")
                .param("q", "差旅报销")).andExpect(status().isOk()).andReturn());
    assertThat(JsonPath.<String>read(search, "$.data.status")).isEqualTo("READY");
    assertThat(JsonPath.<String>read(search, "$.data.items[0].validity")).isEqualTo("EXPIRED");
    assertThat(JsonPath.<Integer>read(search, "$.data.items[0].versionNumber")).isEqualTo(1);

    String conversationId =
        JsonPath.read(
            body(
                mvc.perform(
                        post("/api/v1/conversations")
                            .header("Idempotency-Key", "policy-conversation-1")
                            .header("X-Dev-Subject", ADMIN)
                            .with(csrf())
                            .contentType("application/json")
                            .content("{\"assistantId\":\"%s\"}".formatted(assistantId)))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.conversationId");
    String questionId =
        JsonPath.read(
            body(
                mvc.perform(
                        post("/api/v1/conversations/{id}/questions", conversationId)
                            .header("X-Dev-Subject", ADMIN)
                            .header("Idempotency-Key", "pol-question-1")
                            .with(csrf())
                            .contentType("application/json")
                            .content("{\"text\":\"差旅报销时限是多久？\"}"))
                    .andExpect(status().isAccepted())
                    .andReturn()),
            "$.data.questionId");
    for (int round = 0; round < 6 && !"SUCCEEDED".equals(
            JsonPath.read(body(callAs(ADMIN, get("/api/v1/questions/" + questionId))
                .andReturn()), "$.data.status")); round++) {
      answerWorker.poll();
    }

    String answer =
        body(callAs(ADMIN, get("/api/v1/questions/" + questionId)).andExpect(status().isOk())
            .andReturn());
    assertThat(JsonPath.<List<String>>read(answer, "$.data.paragraphs[*].kind"))
        .containsExactly("EXPIRED");
    // Token 用量已上报，不再恒为空。
    assertThat(JsonPath.<Number>read(answer, "$.data.tokenTotal").longValue()).isPositive();

    // 同一份文档改成「有效性待核实」后，新发布的回答段落类型随之变化。
    String unknownEtag =
        callAs(ADMIN, get("/api/v1/spaces/" + spaceId + "/draft")).andReturn()
            .getResponse().getHeader("ETag");
    String unknownSavedEtag =
        mvc.perform(
                put("/api/v1/spaces/{spaceId}/draft", spaceId)
                    .header("X-Dev-Subject", ADMIN)
                    .header("If-Match", unknownEtag)
                    .with(csrf())
                    .contentType("application/json")
                    .content(("{\"entries\":[{\"documentVersionId\":\"%s\",\"policyState\":\"UNKNOWN\"}]}")
                        .formatted(docId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getHeader("ETag");
    String secondRequest =
        JsonPath.read(
            body(
                mvc.perform(
                        post("/api/v1/spaces/{spaceId}/publish-requests", spaceId)
                            .header("X-Dev-Subject", ADMIN)
                            .header("If-Match", unknownSavedEtag)
                            .header("Idempotency-Key", "pol-submit-2")
                            .with(csrf())
                            .contentType("application/json")
                            .content("{\"reason\":\"待核实政策\"}"))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.requestId");
    String secondEtag =
        callAs(ADMIN, get("/api/v1/publish-requests/" + secondRequest)).andReturn()
            .getResponse().getHeader("ETag");
    mvc.perform(
            post("/api/v1/publish-requests/{requestId}/decision", secondRequest)
                .header("X-Dev-Subject", ADMIN)
                .header("If-Match", secondEtag)
                .header("Idempotency-Key", "pol-decide-2")
                .with(csrf())
                .contentType("application/json")
                .content("{\"decision\":\"APPROVE\"}"))
        .andExpect(status().isOk());
    publishWorker.poll();

    String secondQuestion =
        JsonPath.read(
            body(
                mvc.perform(
                        post("/api/v1/conversations/{id}/questions", conversationId)
                            .header("X-Dev-Subject", ADMIN)
                            .header("Idempotency-Key", "pol-question-2")
                            .with(csrf())
                            .contentType("application/json")
                            .content("{\"text\":\"差旅报销时限是多久？\"}"))
                    .andExpect(status().isAccepted())
                    .andReturn()),
            "$.data.questionId");
    for (int round = 0; round < 6 && !"SUCCEEDED".equals(
            JsonPath.read(body(callAs(ADMIN, get("/api/v1/questions/" + secondQuestion))
                .andReturn()), "$.data.status")); round++) {
      answerWorker.poll();
    }
    assertThat(JsonPath.<List<String>>read(
            body(callAs(ADMIN, get("/api/v1/questions/" + secondQuestion)).andReturn()),
            "$.data.paragraphs[*].kind"))
        .containsExactly("UNCERTAIN");
  }

}
