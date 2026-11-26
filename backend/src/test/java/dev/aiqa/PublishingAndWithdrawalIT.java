package dev.aiqa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.aiqa.testsupport.TestDatabase;
import dev.aiqa.worker.AnswerWorker;
import dev.aiqa.worker.DocumentParseWorker;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * End-to-end proof of the design's central promises against real PostgreSQL: unapproved content
 * never becomes visible, publishing is atomic, and withdrawal masks historical paragraphs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublishingAndWithdrawalIT {

  private static final TestDatabase DATABASE = TestDatabase.fromEnvironment().orElse(null);
  private static final String ADMIN = "it-admin";
  private static final String READER = "it-reader";
  private static final Path OBJECT_ROOT = createTempRoot();

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

  private static Path createTempRoot() {
    try {
      return Files.createTempDirectory("aiqa-objects-");
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private DocumentParseWorker parseWorker;
  @Autowired private AnswerWorker answerWorker;
  @Autowired private dev.aiqa.worker.IndexRebuildWorker indexRebuildWorker;

  @BeforeAll
  void requireDatabase() {
    Assumptions.assumeTrue(DATABASE != null, "未配置 IT_DB_* 测试数据库，跳过 PostgreSQL 集成测试");
    assertThat(jdbc.sql("SELECT count(*) FROM space").query(Long.class).single()).isZero();
  }

  @AfterAll
  void cleanUp() {
    if (DATABASE != null) {
      DATABASE.close();
    }
  }

  private ResultActions call(MockHttpServletRequestBuilder builder) throws Exception {
    return mvc.perform(
        builder.header("X-Dev-Subject", ADMIN).header("X-Dev-Name", "集成测试管理员").with(csrf()));
  }

  private ResultActions callAs(String subject, MockHttpServletRequestBuilder builder) throws Exception {
    return mvc.perform(
        builder.header("X-Dev-Subject", subject).header("X-Dev-Name", "集成测试读者").with(csrf()));
  }

  private ResultActions upload(MockMultipartHttpServletRequestBuilder builder) throws Exception {
    return mvc.perform(
        builder.header("X-Dev-Subject", ADMIN).header("X-Dev-Name", "集成测试管理员").with(csrf()));
  }

  private void grantAdmin() {
    jdbc.sql(
            """
            UPDATE user_role SET role = 'SYSTEM_ADMIN'
            WHERE user_id = (SELECT id FROM app_user WHERE subject = :subject)
            """)
        .param("subject", ADMIN)
        .update();
  }

  /** 给指定账号授予空间只读成员关系；granted_by 记为管理员，满足 NOT NULL 约束。 */
  private void grantMembership(String spaceId, String subject) {
    jdbc.sql(
            """
            INSERT INTO space_member (space_id, user_id, can_read, can_maintain, granted_by)
            SELECT :space, member.id, true, false, admin.id
            FROM app_user member, app_user admin
            WHERE member.subject = :subject AND admin.subject = :admin
            """)
        .param("space", java.util.UUID.fromString(spaceId))
        .param("subject", subject)
        .param("admin", ADMIN)
        .update();
  }

  /** 撤销空间成员关系，用于验证撤权后历史引用不再可读。 */
  private void revokeMembership(String spaceId, String subject) {
    jdbc.sql(
            """
            DELETE FROM space_member
            WHERE space_id = :space AND user_id = (SELECT id FROM app_user WHERE subject = :subject)
            """)
        .param("space", java.util.UUID.fromString(spaceId))
        .param("subject", subject)
        .update();
  }

  private static MockMultipartFile markdown(String name, String body) {
    return new MockMultipartFile("files", name, "text/markdown", body.getBytes(StandardCharsets.UTF_8));
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void unapprovedContentStaysHiddenAndWithdrawalMasksHistory() throws Exception {
    call(get("/api/v1/auth/me")).andExpect(status().isOk());
    grantAdmin();
    call(get("/api/v1/auth/me")).andExpect(status().isOk());

    MvcResult created =
        call(post("/api/v1/spaces")
                .contentType("application/json")
                .content("{\"name\":\"集成测试政策库\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String spaceId = JsonPath.read(body(created), "$.data.spaceId");

    MvcResult imported =
        upload(multipart("/api/v1/spaces/{spaceId}/imports", spaceId)
                .file(markdown("差旅报销办法.md", "国内差旅应在行程结束后十五个工作日内提交报销申请，并附上发票与行程单。\n\n内部培训由人力资源部门组织。"))
                .file(markdown("住宿标准.md", "一线城市住宿上限为每晚四百五十元，凭住宿发票报销。"))
                .file(markdown("差旅报销办法.md", "重复文件不应覆盖。"))
                .header("Idempotency-Key", "it-import-1"))
            .andExpect(status().isAccepted())
            .andReturn();
    String importJson = body(imported);
    assertThat(JsonPath.<List<String>>read(importJson, "$.data.files[*].status"))
        .containsExactly("QUEUED", "QUEUED", "SKIPPED_DUPLICATE");
    String travelDocId = JsonPath.read(importJson, "$.data.files[0].documentVersionId");

    parseWorker.poll();

    MvcResult draft =
        call(get("/api/v1/spaces/{spaceId}/draft", spaceId)).andExpect(status().isOk()).andReturn();
    String draftEtag = draft.getResponse().getHeader("ETag");
    assertThat(draftEtag).isNotBlank();

    MvcResult saved =
        call(put("/api/v1/spaces/{spaceId}/draft", spaceId)
                .header("If-Match", draftEtag)
                .contentType("application/json")
                .content(
                    "{\"entries\":[{\"documentVersionId\":\"%s\",\"policyState\":\"EFFECTIVE\"}]}"
                        .formatted(travelDocId)))
            .andExpect(status().isOk())
            .andReturn();
    String savedEtag = saved.getResponse().getHeader("ETag");
    assertThat(savedEtag).isNotEqualTo(draftEtag);

    // 未发布：员工侧不可见；系统管理员可检索草稿，并被告知来源。
    callAs(READER, get("/api/v1/spaces/{spaceId}/search", spaceId).param("q", "差旅"))
        .andExpect(status().isNotFound());
    MvcResult draftSearch =
        call(get("/api/v1/spaces/{spaceId}/search", spaceId).param("q", "差旅"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(JsonPath.<String>read(body(draftSearch), "$.data.contentSource")).isEqualTo("DRAFT");

    MvcResult submitted =
        call(post("/api/v1/spaces/{spaceId}/publish-requests", spaceId)
                .header("Idempotency-Key", "it-submit-1")
                .header("If-Match", savedEtag)
                .contentType("application/json")
                .content("{\"reason\":\"首次发布审核\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String requestId = JsonPath.read(body(submitted), "$.data.requestId");
    assertThat(JsonPath.<String>read(body(submitted), "$.data.status")).isEqualTo("PENDING_REVIEW");

    String requestEtag =
        call(get("/api/v1/publish-requests/{requestId}", requestId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("ETag");

    MvcResult decided =
        call(post("/api/v1/publish-requests/{requestId}/decision", requestId)
                .header("Idempotency-Key", "it-decision-1")
                .header("If-Match", requestEtag)
                .contentType("application/json")
                .content("{\"decision\":\"APPROVE\"}"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(JsonPath.<String>read(body(decided), "$.data.status")).isEqualTo("PUBLISHED");

    String publicationId =
        JsonPath.read(
            body(
                call(get("/api/v1/spaces/{spaceId}", spaceId))
                    .andExpect(status().isOk())
                    .andReturn()),
            "$.data.currentPublicationId");
    assertThat(publicationId).isNotBlank();

    MvcResult search =
        call(get("/api/v1/spaces/{spaceId}/search", spaceId).param("q", "十五个工作日"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(JsonPath.<String>read(body(search), "$.data.status")).isEqualTo("READY");
    assertThat(JsonPath.<List<String>>read(body(search), "$.data.items[*].name"))
        .anyMatch(name -> name.contains("差旅报销办法"));

    String assistantId =
        JsonPath.read(
            body(
                call(post("/api/v1/assistants")
                        .header("Idempotency-Key", "it-assistant-1")
                        .contentType("application/json")
                        .content(
                            "{\"name\":\"政策助手\",\"bindings\":[{\"spaceId\":\"%s\",\"role\":\"RETRIEVAL\"}],\"templateId\":null}"
                                .formatted(spaceId)))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.assistantId");

    String conversationId =
        JsonPath.read(
            body(
                call(post("/api/v1/conversations")
                            .header("Idempotency-Key", "it-conversation-1")
                        .contentType("application/json")
                        .content("{\"assistantId\":\"%s\"}".formatted(assistantId)))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.conversationId");

    String questionId =
        JsonPath.read(
            body(
                call(post("/api/v1/conversations/{conversationId}/questions", conversationId)
                        .header("Idempotency-Key", "it-question-1")
                        .contentType("application/json")
                        .content("{\"text\":\"国内差旅报销时限是多少？\"}"))
                    .andExpect(status().isAccepted())
                    .andReturn()),
            "$.data.questionId");
    answerWorker.poll();

    String answeredJson =
        body(call(get("/api/v1/questions/{questionId}", questionId)).andExpect(status().isOk()).andReturn());
    assertThat(JsonPath.<String>read(answeredJson, "$.data.paragraphs[0].status")).isEqualTo("VISIBLE");
    assertThat(JsonPath.<String>read(answeredJson, "$.data.paragraphs[0].text")).contains("十五个工作日");
    String citationId = JsonPath.read(answeredJson, "$.data.paragraphs[0].citations[0].citationId");
    assertThat(JsonPath.<List<String>>read(answeredJson, "$.data.paragraphs[0].citations[*].label"))
        .anyMatch(label -> label.contains("差旅报销办法"));

    String preview = body(call(get("/api/v1/citations/{citationId}", citationId))
        .andExpect(status().isOk()).andReturn());
    assertThat(JsonPath.<String>read(preview, "$.data.evidenceGranularity")).isEqualTo("CHUNK");
    assertThat(JsonPath.<String>read(preview, "$.data.documentVersionId")).isEqualTo(travelDocId);
    assertThat(JsonPath.<String>read(preview, "$.data.previewText"))
        .contains("十五个工作日").doesNotContain("内部培训");

    call(post("/api/v1/spaces/{spaceId}/reindex", spaceId)
            .header("Idempotency-Key", "it-rebuild-after-answer"))
        .andExpect(status().isOk());
    indexRebuildWorker.poll();
    assertThat(JsonPath.<Object>read(body(call(get("/api/v1/citations/{citationId}", citationId))
        .andExpect(status().isOk()).andReturn()), "$.data"))
        .isEqualTo(JsonPath.<Object>read(preview, "$.data"));

    String unmatchedQuestion = JsonPath.read(body(call(
        post("/api/v1/conversations/{conversationId}/questions", conversationId)
            .header("Idempotency-Key", "it-question-no-evidence")
            .contentType("application/json").content("{\"text\":\"？！\"}"))
        .andExpect(status().isAccepted()).andReturn()), "$.data.questionId");
    answerWorker.poll();
    String noEvidence = body(call(get("/api/v1/questions/{questionId}", unmatchedQuestion))
        .andExpect(status().isOk()).andReturn());
    assertThat(JsonPath.<String>read(noEvidence, "$.data.paragraphs[0].kind")).isEqualTo("NO_EVIDENCE");
    assertThat(JsonPath.<String>read(noEvidence, "$.data.paragraphs[0].status")).isEqualTo("VISIBLE");
    assertThat(JsonPath.<List<String>>read(noEvidence, "$.data.paragraphs[0].citations")).isEmpty();

    // 系统管理员在 paragraphStillAuthorized 里有显式放行：撤掉成员关系仍能读引用、正文与检索。
    for (boolean member : List.of(false, true)) {
      String membershipSql = member ? """
          INSERT INTO space_member (space_id, user_id, can_read, can_maintain, granted_by)
          SELECT :space, id, true, true, id FROM app_user WHERE subject = :subject
          """ : """
          DELETE FROM space_member
          WHERE space_id = :space AND user_id = (SELECT id FROM app_user WHERE subject = :subject)
          """;
      jdbc.sql(membershipSql).param("space", java.util.UUID.fromString(spaceId))
          .param("subject", ADMIN).update();
      call(get("/api/v1/citations/{citationId}", citationId)).andExpect(status().isOk());
      call(get("/api/v1/citations/{citationId}/content", citationId)).andExpect(status().isOk());
      call(get("/api/v1/spaces/{spaceId}/search", spaceId).param("q", "十五个工作日"))
          .andExpect(status().isOk());
    }

    // 普通成员对照：同一空间、同一助手、同一批证据，撤权后引用与正文都必须 404。
    grantMembership(spaceId, READER);
    String readerConversationId =
        JsonPath.read(
            body(
                callAs(READER,
                        post("/api/v1/conversations")
                            .header("Idempotency-Key", "it-conversation-reader")
                            .contentType("application/json")
                            .content("{\"assistantId\":\"%s\"}".formatted(assistantId)))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.conversationId");
    String readerQuestionId =
        JsonPath.read(
            body(
                callAs(READER,
                        post("/api/v1/conversations/{conversationId}/questions", readerConversationId)
                            .header("Idempotency-Key", "it-question-reader")
                            .contentType("application/json")
                            .content("{\"text\":\"国内差旅报销时限是多少？\"}"))
                    .andExpect(status().isAccepted())
                    .andReturn()),
            "$.data.questionId");
    answerWorker.poll();
    String readerCitationId =
        JsonPath.read(
            body(
                callAs(READER, get("/api/v1/questions/{questionId}", readerQuestionId))
                    .andExpect(status().isOk())
                    .andReturn()),
            "$.data.paragraphs[0].citations[0].citationId");
    callAs(READER, get("/api/v1/citations/{citationId}", readerCitationId))
        .andExpect(status().isOk());
    callAs(READER, get("/api/v1/citations/{citationId}/content", readerCitationId))
        .andExpect(status().isOk());
    // 撤权：成员关系删除后，历史引用的预览、原件与段落本身都不再对普通账号可读。
    revokeMembership(spaceId, READER);
    callAs(READER, get("/api/v1/citations/{citationId}", readerCitationId))
        .andExpect(status().isNotFound());
    callAs(READER, get("/api/v1/citations/{citationId}/content", readerCitationId))
        .andExpect(status().isNotFound());
    assertThat(
            JsonPath.<String>read(
                body(
                    callAs(READER, get("/api/v1/questions/{questionId}", readerQuestionId))
                        .andExpect(status().isOk())
                        .andReturn()),
                "$.data.paragraphs[0].status"))
        .isEqualTo("MASKED");

    // Keep the first conversation for withdrawal checks and delete a second while its evidence is valid.
    String deletedConversationId = JsonPath.read(body(call(post("/api/v1/conversations")
            .header("Idempotency-Key", "it-conversation-deleted")
            .contentType("application/json")
            .content("{\"assistantId\":\"%s\"}".formatted(assistantId)))
        .andExpect(status().isCreated()).andReturn()), "$.data.conversationId");
    String deletedQuestionId = JsonPath.read(body(call(post(
            "/api/v1/conversations/{id}/questions", deletedConversationId)
            .header("Idempotency-Key", "it-question-deleted")
            .contentType("application/json")
            .content("{\"text\":\"国内差旅报销时限是多少？\"}"))
        .andExpect(status().isAccepted()).andReturn()), "$.data.questionId");
    answerWorker.poll();
    String deletedCitationId = JsonPath.read(body(call(get("/api/v1/questions/{id}", deletedQuestionId))
        .andExpect(status().isOk()).andReturn()), "$.data.paragraphs[0].citations[0].citationId");
    call(put("/api/v1/conversations/{id}/archive", deletedConversationId)
            .contentType("application/json").content("{\"archived\":true}"))
        .andExpect(status().isOk());
    call(get("/api/v1/citations/{id}", deletedCitationId)).andExpect(status().isOk());
    call(get("/api/v1/citations/{id}/content", deletedCitationId)).andExpect(status().isOk());
    call(delete("/api/v1/conversations/{id}", deletedConversationId)).andExpect(status().isNoContent());
    call(get("/api/v1/citations/{id}", deletedCitationId)).andExpect(status().isNotFound());
    call(get("/api/v1/citations/{id}/content", deletedCitationId)).andExpect(status().isNotFound());

    // An approved request that is not yet published must not leak new content.
    MvcResult reimport =
        upload(multipart("/api/v1/spaces/{spaceId}/imports", spaceId)
                .file(markdown("新住宿规定.md", "二线城市住宿上限为每晚三百元。"))
                .header("Idempotency-Key", "it-import-2"))
            .andExpect(status().isAccepted())
            .andReturn();
    String newDocId = JsonPath.read(body(reimport), "$.data.files[0].documentVersionId");
    parseWorker.poll();
    String existingEtag =
        call(get("/api/v1/spaces/{spaceId}/draft", spaceId)).andReturn().getResponse().getHeader("ETag");
    String expandedEtag =
        call(put("/api/v1/spaces/{spaceId}/draft", spaceId)
                .header("If-Match", existingEtag)
                .contentType("application/json")
                .content(
                    "{\"entries\":[{\"documentVersionId\":\"%s\",\"policyState\":\"EFFECTIVE\"},{\"documentVersionId\":\"%s\",\"policyState\":\"EFFECTIVE\"}]}"
                        .formatted(travelDocId, newDocId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("ETag");
    String expandedRequest = submit(spaceId, expandedEtag, "it-submit-2");

    MvcResult pendingSearch =
        call(get("/api/v1/spaces/{spaceId}/search", spaceId).param("q", "二线城市"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(JsonPath.<List<String>>read(body(pendingSearch), "$.data.items[*].name"))
        .noneMatch(name -> name.contains("新住宿规定"));
    assertThat(
            JsonPath.<String>read(
                body(
                    call(get("/api/v1/spaces/{spaceId}/search", spaceId).param("q", "十五个工作日"))
                        .andExpect(status().isOk())
                        .andReturn()),
                "$.data.status"))
        .isEqualTo("READY");

    publicationId = approve(expandedRequest, "it-decision-2", "PUBLISHED");
    assertThat(JsonPath.<String>read(body(call(get("/api/v1/questions/{id}", questionId))
        .andExpect(status().isOk()).andReturn()), "$.data.paragraphs[0].status")).isEqualTo("VISIBLE");
    assertThat(JsonPath.<Object>read(body(call(get("/api/v1/citations/{id}", citationId))
        .andExpect(status().isOk()).andReturn()), "$.data"))
        .isEqualTo(JsonPath.<Object>read(preview, "$.data"));
    call(get("/api/v1/citations/{id}/content", citationId)).andExpect(status().isOk());

    // Queue both kinds of ingestion work and approve a snapshot that still needs parsing.
    call(post("/api/v1/spaces/{spaceId}/reindex", spaceId)
            .header("Idempotency-Key", "it-rebuild-pending"))
        .andExpect(status().isOk());
    String queuedDocId = JsonPath.read(body(upload(multipart("/api/v1/spaces/{spaceId}/imports", spaceId)
            .file(markdown("待解析规定.md", "内部审批须保留记录。"))
            .header("Idempotency-Key", "it-import-pending"))
        .andExpect(status().isAccepted()).andReturn()), "$.data.files[0].documentVersionId");
    String pendingEtag = call(put("/api/v1/spaces/{spaceId}/draft", spaceId)
            .header("If-Match", draftEtag(spaceId))
            .contentType("application/json")
            .content("{\"entries\":[{\"documentVersionId\":\"%s\",\"policyState\":\"EFFECTIVE\"},{\"documentVersionId\":\"%s\",\"policyState\":\"EFFECTIVE\"}]}"
                .formatted(travelDocId, queuedDocId)))
        .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
    String waitingRequest = submit(spaceId, pendingEtag, "it-submit-pending");
    approve(waitingRequest, "it-decision-pending", "APPROVED_WAITING_INDEX");
    assertThat(jobCount(spaceId, "PARSE_DOCUMENT", "QUEUED")).isPositive();
    assertThat(jobCount(spaceId, "INDEX_DOCUMENT", "QUEUED")).isPositive();
    assertThat(jobCount(spaceId, "PUBLISH_SNAPSHOT", "QUEUED")).isEqualTo(1);

    call(post("/api/v1/spaces/{spaceId}/withdrawals", spaceId)
            .header("Idempotency-Key", "it-withdraw-1")
            .contentType("application/json")
            .content("{\"expectedPublicationId\":\"%s\",\"reason\":\"政策整体废止\"}".formatted(publicationId)))
        .andExpect(status().isOk());
    // 撤回后员工侧不再可见；系统管理员仍可检索最近一次发布快照。
    callAs(READER, get("/api/v1/spaces/{spaceId}/search", spaceId).param("q", "十五个工作日"))
        .andExpect(status().isNotFound());
    MvcResult withdrawnSearch =
        call(get("/api/v1/spaces/{spaceId}/search", spaceId).param("q", "十五个工作日"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(JsonPath.<String>read(body(withdrawnSearch), "$.data.contentSource"))
        .isEqualTo("WITHDRAWN");
    assertThat(JsonPath.<List<String>>read(body(withdrawnSearch), "$.data.items[*].name"))
        .anyMatch(name -> name.contains("差旅报销办法"));
    // source 显式指定：published 不存在当前发布，draft 仍可读，latest 与 auto 一致。
    call(get("/api/v1/spaces/{spaceId}/search", spaceId)
            .param("q", "十五个工作日").param("source", "published"))
        .andExpect(status().isNotFound());
    assertThat(JsonPath.<String>read(
            body(call(get("/api/v1/spaces/{spaceId}/search", spaceId)
                    .param("q", "十五个工作日").param("source", "draft"))
                .andExpect(status().isOk()).andReturn()),
            "$.data.contentSource"))
        .isEqualTo("DRAFT");
    assertThat(JsonPath.<String>read(
            body(call(get("/api/v1/spaces/{spaceId}/search", spaceId)
                    .param("q", "十五个工作日").param("source", "latest"))
                .andExpect(status().isOk()).andReturn()),
            "$.data.contentSource"))
        .isEqualTo("WITHDRAWN");
    // 命中项必须点得开：管理员可预览/下载已撤回快照内的文档，普通用户仍然 404。
    call(get("/api/v1/spaces/{spaceId}/documents/{documentId}", spaceId, travelDocId))
        .andExpect(status().isOk());
    call(get("/api/v1/spaces/{spaceId}/documents/{documentId}/content", spaceId, travelDocId))
        .andExpect(status().isOk());
    callAs(READER, get("/api/v1/spaces/{spaceId}/documents/{documentId}", spaceId, travelDocId))
        .andExpect(status().isNotFound());
    // 非管理员不能把检索范围指向未发布内容。
    callAs(READER, get("/api/v1/spaces/{spaceId}/search", spaceId)
            .param("q", "十五个工作日").param("source", "draft"))
        .andExpect(status().isUnprocessableEntity());

    String maskedJson =
        body(
            call(get("/api/v1/questions/{questionId}", questionId))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(JsonPath.<String>read(maskedJson, "$.data.paragraphs[0].status")).isEqualTo("MASKED");
    assertThat(JsonPath.<String>read(maskedJson, "$.data.paragraphs[0].code")).isEqualTo("SOURCE_UNAVAILABLE");
    assertThat(maskedJson)
        .doesNotContain("十五个工作日")
        .doesNotContainIgnoringCase("citationId")
        .doesNotContain("差旅报销办法");
    call(get("/api/v1/citations/{citationId}", citationId)).andExpect(status().isNotFound());

    MvcResult pendingRequests =
        call(get("/api/v1/spaces/{spaceId}/publish-requests", spaceId))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(JsonPath.<List<String>>read(body(pendingRequests), "$.data.items[*].status"))
        .contains("CANCELLED");
    assertThat(jobCount(spaceId, "PUBLISH_SNAPSHOT", "CANCELLED")).isEqualTo(1);
    assertThat(jobCount(spaceId, "PARSE_DOCUMENT", "QUEUED")).isPositive();
    assertThat(jobCount(spaceId, "INDEX_DOCUMENT", "QUEUED")).isPositive();
    parseWorker.poll();
    indexRebuildWorker.poll();

    String republishRequest = submit(spaceId, draftEtag(spaceId), "it-submit-republish");
    approve(republishRequest, "it-decision-republish", "PUBLISHED");
    assertThat(JsonPath.<String>read(body(call(get("/api/v1/questions/{id}", questionId))
        .andExpect(status().isOk()).andReturn()), "$.data.paragraphs[0].status")).isEqualTo("MASKED");
    call(get("/api/v1/citations/{id}", citationId)).andExpect(status().isNotFound());
    call(get("/api/v1/citations/{id}/content", citationId)).andExpect(status().isNotFound());
  }

  private String draftEtag(String spaceId) throws Exception {
    return call(get("/api/v1/spaces/{spaceId}/draft", spaceId))
        .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
  }

  private String submit(String spaceId, String etag, String key) throws Exception {
    return JsonPath.read(body(call(post("/api/v1/spaces/{spaceId}/publish-requests", spaceId)
            .header("Idempotency-Key", key).header("If-Match", etag)
            .contentType("application/json").content("{\"reason\":\"提交发布\"}"))
        .andExpect(status().isCreated()).andReturn()), "$.data.requestId");
  }

  private String approve(String requestId, String key, String expectedStatus) throws Exception {
    String etag = call(get("/api/v1/publish-requests/{id}", requestId))
        .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
    String json = body(call(post("/api/v1/publish-requests/{id}/decision", requestId)
            .header("Idempotency-Key", key).header("If-Match", etag)
            .contentType("application/json").content("{\"decision\":\"APPROVE\"}"))
        .andExpect(status().isOk()).andReturn());
    assertThat(JsonPath.<String>read(json, "$.data.status")).isEqualTo(expectedStatus);
    return "PUBLISHED".equals(expectedStatus) ? JsonPath.read(json, "$.data.publicationId") : null;
  }

  private long jobCount(String spaceId, String kind, String status) {
    return jdbc.sql("SELECT count(*) FROM job WHERE space_id = :space AND job_type = :kind AND status = :status")
        .param("space", java.util.UUID.fromString(spaceId)).param("kind", kind).param("status", status)
        .query(Long.class).single();
  }
}
