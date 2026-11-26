package dev.aiqa;

import static dev.aiqa.testsupport.ApiResponseAssertions.problem;
import static dev.aiqa.testsupport.ApiResponseAssertions.successEnvelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.aiqa.testsupport.TestDatabase;
import dev.aiqa.worker.DocumentParseWorker;
import dev.aiqa.worker.PublishWorker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Covers the endpoints the contract declares beyond the happy-path flows: global request
 * listing, review document preview, citation download, logout and the assistant config read.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractEndpointsIT {

  private static final TestDatabase DATABASE = TestDatabase.fromEnvironment().orElse(null);
  private static final String ADMIN = "ctr-admin";
  private static final String READER = "ctr-reader";
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
    registry.add("aiqa.rate-limit.writes-per-minute", () -> "200");
  }

  private static Path tempRoot() {
    try {
      return Files.createTempDirectory("aiqa-objects-contract-");
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private DocumentParseWorker parseWorker;
  @Autowired private PublishWorker publishWorker;

  @BeforeAll
  void prepare() throws Exception {
    Assumptions.assumeTrue(DATABASE != null, "未配置 IT_DB_* 测试数据库，跳过 PostgreSQL 集成测试");
    for (String subject : List.of(ADMIN, READER)) {
      mvc.perform(get("/api/v1/auth/me").header("X-Dev-Subject", subject)).andExpect(status().isOk());
    }
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

  private org.springframework.test.web.servlet.ResultActions as(String subject, String path)
      throws Exception {
    return mvc.perform(get(path).header("X-Dev-Subject", subject));
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void declaredEndpointsBehaveAsTheContractSays() throws Exception {
    String spaceId =
        JsonPath.read(
            body(
                mvc.perform(
                        post("/api/v1/spaces")
                            .header("X-Dev-Subject", ADMIN)
                            .with(csrf())
                            .contentType("application/json")
                            .content("{\"name\":\"契约端点测试库\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(successEnvelope())
                    .andReturn()),
            "$.data.spaceId");

    MockMultipartFile file =
        new MockMultipartFile("files", "契约文档.md", "text/markdown",
            "契约端点正文：报销须在三十日内提交。".getBytes(StandardCharsets.UTF_8));
    String docId =
        JsonPath.read(
            body(
                mvc.perform(
                        multipart("/api/v1/spaces/{spaceId}/imports", spaceId)
                            .file(file)
                            .header("X-Dev-Subject", ADMIN)
                            .header("Idempotency-Key", "ctr-import-1")
                            .with(csrf()))
                    .andExpect(status().isAccepted())
                    .andExpect(successEnvelope())
                    .andReturn()),
            "$.data.files[0].documentVersionId");
    parseWorker.poll();

    mvc.perform(get("/api/v1/auth/me").header("X-Dev-Subject", ADMIN)
            .header("X-Request-Id", "contract-me-request"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Request-Id", "contract-me-request"))
        .andExpect(successEnvelope());

    String etag = as(ADMIN, "/api/v1/spaces/" + spaceId + "/draft").andReturn().getResponse().getHeader("ETag");
    String savedEtag =
        mvc.perform(
                put("/api/v1/spaces/{spaceId}/draft", spaceId)
                    .header("X-Dev-Subject", ADMIN)
                    .header("If-Match", etag)
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"entries\":[{\"documentVersionId\":\"%s\",\"policyState\":\"EFFECTIVE\"}]}"
                        .formatted(docId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("ETag");

    mvc.perform(put("/api/v1/spaces/{spaceId}/draft", spaceId)
            .header("X-Dev-Subject", ADMIN)
            .header("If-Match", etag)
            .with(csrf())
            .contentType("application/json")
            .content("{\"entries\":[]}"))
        .andExpect(status().isPreconditionFailed())
        .andExpect(problem("ETAG_MISMATCH"));

    // Publishing an assistant returns the ETag the contract requires.
    MvcResult assistant =
        mvc.perform(
                post("/api/v1/assistants")
                    .header("X-Dev-Subject", ADMIN)
                    .header("Idempotency-Key", "ctr-assistant-1")
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"name\":\"契约助手\",\"bindings\":[{\"spaceId\":\"%s\",\"role\":\"RETRIEVAL\"}],\"templateId\":null}"
                        .formatted(spaceId)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("ETag"))
            .andReturn();
    String assistantId = JsonPath.read(body(assistant), "$.data.assistantId");

    // 管理员可停用助手，普通用户列表随即看不到它。
    String assistantEtag =
        as(ADMIN, "/api/v1/assistants/" + assistantId).andReturn().getResponse().getHeader("ETag");
    mvc.perform(
            put("/api/v1/assistants/{id}", assistantId)
                .header("X-Dev-Subject", ADMIN)
                .header("If-Match", assistantEtag)
                .header("Idempotency-Key", "ctr-assistant-off")
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\"契约助手\",\"bindings\":[{\"spaceId\":\"%s\",\"role\":\"RETRIEVAL\"}],\"templateId\":null,\"enabled\":false}"
                    .formatted(spaceId)))
        .andExpect(status().isOk());
    assertThat(JsonPath.<List<String>>read(
            body(as(READER, "/api/v1/assistants").andExpect(status().isOk()).andReturn()),
            "$.data.items[*].assistantId"))
        .doesNotContain(assistantId);
    String reEnableEtag =
        as(ADMIN, "/api/v1/assistants/" + assistantId).andReturn().getResponse().getHeader("ETag");
    mvc.perform(
            put("/api/v1/assistants/{id}", assistantId)
                .header("X-Dev-Subject", ADMIN)
                .header("If-Match", reEnableEtag)
                .header("Idempotency-Key", "ctr-assistant-on")
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\"契约助手\",\"bindings\":[{\"spaceId\":\"%s\",\"role\":\"RETRIEVAL\"}],\"templateId\":null,\"enabled\":true}"
                    .formatted(spaceId)))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .jsonPath("$.data.enabled").value(true));

    mvc.perform(put("/api/v1/assistants/{id}", assistantId)
            .header("X-Dev-Subject", ADMIN)
            .header("If-Match", assistantEtag)
            .header("Idempotency-Key", "ctr-assistant-stale")
            .with(csrf())
            .contentType("application/json")
            .content("{\"name\":\"契约助手\",\"bindings\":[]}"))
        .andExpect(status().isPreconditionFailed())
        .andExpect(problem("ETAG_MISMATCH"));

    // Admin config read exposes the resolved template; the user-facing list does not.
    String config =
        body(as(ADMIN, "/api/v1/assistants/" + assistantId).andExpect(status().isOk()).andReturn());
    assertThat(JsonPath.<String>read(config, "$.data.bindings[0].spaceId")).isEqualTo(spaceId);
    as(READER, "/api/v1/assistants/" + assistantId).andExpect(status().isForbidden());

    // Submit and approve so a publication and a request listing exist.
    mvc.perform(post("/api/v1/spaces/{spaceId}/publish-requests", spaceId)
            .header("X-Dev-Subject", ADMIN)
            .header("If-Match", etag)
            .header("Idempotency-Key", "ctr-submit-stale")
            .with(csrf())
            .contentType("application/json")
            .content("{\"reason\":\"契约测试\"}"))
        .andExpect(status().isPreconditionFailed())
        .andExpect(problem("ETAG_MISMATCH"));
    String requestId =
        JsonPath.read(
            body(
                mvc.perform(
                        post("/api/v1/spaces/{spaceId}/publish-requests", spaceId)
                            .header("X-Dev-Subject", ADMIN)
                            .header("If-Match", savedEtag)
                            .header("Idempotency-Key", "ctr-submit-1")
                            .with(csrf())
                            .contentType("application/json")
                            .content("{\"reason\":\"契约测试\"}"))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.requestId");
    String requestEtag =
        as(ADMIN, "/api/v1/publish-requests/" + requestId)
            .andReturn()
            .getResponse()
            .getHeader("ETag");

    mvc.perform(post("/api/v1/publish-requests/{requestId}/decision", requestId)
            .header("X-Dev-Subject", ADMIN)
            .header("If-Match", etag)
            .header("Idempotency-Key", "ctr-decide-stale")
            .with(csrf())
            .contentType("application/json")
            .content("{\"decision\":\"APPROVE\"}"))
        .andExpect(status().isPreconditionFailed())
        .andExpect(problem("ETAG_MISMATCH"));

    String pending =
        body(as(ADMIN, "/api/v1/publish-requests").andExpect(status().isOk()).andReturn());
    assertThat(JsonPath.<List<String>>read(pending, "$.data.items[?(@.requestId=='" + requestId + "')].status"))
        .containsExactly("PENDING_REVIEW");
    assertThat(
            JsonPath.<List<Integer>>read(
                pending, "$.data.items[?(@.requestId=='" + requestId + "')].draftRevision"))
        .isNotEmpty();

    // The reviewer preview works before approval; an unrelated version stays 404.
    String reviewDoc =
        body(
            as(ADMIN, "/api/v1/publish-requests/" + requestId + "/documents/" + docId)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(JsonPath.<String>read(reviewDoc, "$.data.name")).isEqualTo("契约文档.md");
    assertThat(JsonPath.<String>read(reviewDoc, "$.data.previewText")).contains("报销须在三十日内提交");
    // 文档元信息与版本号随预览返回，供界面显示格式/大小与 vN。
    assertThat(JsonPath.<Integer>read(reviewDoc, "$.data.versionNumber")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(reviewDoc, "$.data.sizeBytes")).isGreaterThan(0);
    as(ADMIN, "/api/v1/publish-requests/" + requestId + "/documents/"
            + java.util.UUID.randomUUID())
        .andExpect(status().isNotFound());

    mvc.perform(
            post("/api/v1/publish-requests/{requestId}/decision", requestId)
                .header("X-Dev-Subject", ADMIN)
                .header("If-Match", requestEtag)
                .header("Idempotency-Key", "ctr-decide-1")
                .with(csrf())
                .contentType("application/json")
                .content("{\"decision\":\"APPROVE\"}"))
        .andExpect(status().isOk());
    parseWorker.poll();
    publishWorker.poll();

    String published =
        body(as(ADMIN, "/api/v1/publish-requests/" + requestId).andExpect(status().isOk()).andReturn());
    assertThat(JsonPath.<String>read(published, "$.data.status")).isEqualTo("PUBLISHED");
    assertThat(JsonPath.<String>read(published, "$.data.publicationId")).isNotBlank();
    // 契约要求冻结清单恒在，审核抽屉的「变更内容」依赖它。
    assertThat(JsonPath.<List<String>>read(published, "$.data.snapshot[*].documentVersionId"))
        .containsExactly(docId);
    assertThat(JsonPath.<List<String>>read(published, "$.data.snapshot[*].policyState"))
        .containsExactly("EFFECTIVE");

    // 草稿条目内联文档元信息，客户端不再逐条回查。
    String draft =
        body(as(ADMIN, "/api/v1/spaces/" + spaceId + "/draft").andExpect(status().isOk()).andReturn());
    assertThat(JsonPath.<String>read(draft, "$.data.entries[0].name")).isEqualTo("契约文档.md");
    assertThat(JsonPath.<Integer>read(draft, "$.data.entries[0].versionNumber")).isEqualTo(1);

    // 未发布空间的 currentPublicationId 必须是 null 字段而不是缺失。
    String spaceList = body(as(ADMIN, "/api/v1/spaces").andExpect(status().isOk()).andReturn());
    assertThat(JsonPath.<Object>read(spaceList, "$.data.items[0].currentPublicationId")).isNotNull();
    assertThat(JsonPath.<Object>read(spaceList, "$.data.items[0].updatedAt")).isNotNull();

    // Non-admins only see the requests they submitted.
    assertThat(JsonPath.<List<String>>read(
            body(as(READER, "/api/v1/publish-requests").andExpect(status().isOk()).andReturn()),
            "$.data.items"))
        .isEmpty();

    // Citation download is authorised per citation and serves the original bytes.
    String conversationId =
        JsonPath.read(
            body(
                mvc.perform(
                        post("/api/v1/conversations")
                            .header("Idempotency-Key", "ctr-conversation-1")
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
                            .header("Idempotency-Key", "ctr-question-1")
                            .with(csrf())
                            .contentType("application/json")
                            .content("{\"text\":\"报销要在多久内提交？\"}"))
                    .andExpect(status().isAccepted())
                    .andReturn()),
            "$.data.questionId");
    dev.aiqa.worker.AnswerWorker answerWorker = answerWorker();
    answerWorker.poll();
    answerWorker.poll();
    String citations =
        body(as(ADMIN, "/api/v1/questions/" + questionId).andExpect(status().isOk()).andReturn());
    List<String> citationIds = JsonPath.read(citations, "$.data.paragraphs[*].citations[*].citationId");
    assertThat(citationIds).isNotEmpty();
    as(ADMIN, "/api/v1/citations/" + citationIds.get(0) + "/content")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
        .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(content().bytes(file.getBytes()));
    as(READER, "/api/v1/citations/" + citationIds.get(0) + "/content")
        .andExpect(status().isNotFound());

    // Logout clears the session cookie and ends the session.
    mvc.perform(
            post("/api/v1/auth/logout")
                .header("X-Dev-Subject", ADMIN)
                .with(csrf()))
        .andExpect(status().isNoContent())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(content().bytes(new byte[0]));
  }

  @Autowired
  private dev.aiqa.worker.AnswerWorker answerWorker;

  private dev.aiqa.worker.AnswerWorker answerWorker() {
    return answerWorker;
  }
}
