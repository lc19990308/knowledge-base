package dev.aiqa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.aiqa.cleaning.CleaningWorker;
import dev.aiqa.testsupport.TestDatabase;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 清洗链路端到端：上传文件夹 → 清洗条目 → 审核通过 → 流转成文档版本进空间草稿。
 *
 * <p>模型打分在测试里关闭（只出规则分），发布链路不在本用例范围内，但会验证生成的文档
 * 能被现有解析任务切块，说明它确实接入了既有知识库链路。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CleaningFlowIT {

  private static final TestDatabase DATABASE = TestDatabase.fromEnvironment().orElse(null);
  private static final String ADMIN = "it-cleaning-admin";
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
    registry.add("aiqa.cleaning.model-score", () -> "off");
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private CleaningWorker cleaningWorker;
  @Autowired private DocumentParseWorker parseWorker;

  @BeforeAll
  void requireDatabase() throws Exception {
    Assumptions.assumeTrue(DATABASE != null, "未配置 IT_DB_* 测试数据库，跳过 PostgreSQL 集成测试");
    assertThat(jdbc.sql("SELECT count(*) FROM cleaning_batch").query(Long.class).single()).isZero();
    call(get("/api/v1/auth/me"));
    jdbc.sql(
            """
            UPDATE user_role SET role = 'SYSTEM_ADMIN'
            WHERE user_id = (SELECT id FROM app_user WHERE subject = :subject)
            """)
        .param("subject", ADMIN)
        .update();
    call(get("/api/v1/auth/me"));
  }

  @AfterAll
  void cleanUp() {
    if (DATABASE != null) {
      DATABASE.close();
    }
  }

  @Test
  void uploadCleansReviewsAndPromotesIntoDraft() throws Exception {
    MvcResult created =
        call(
                post("/api/v1/spaces")
                    .contentType("application/json")
                    .content("{\"name\":\"清洗集成测试空间\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String spaceId = JsonPath.read(body(created), "$.data.spaceId");

    String paragraph =
        "差旅住宿标准：一线城市每晚上限 600 元，二线城市 400 元。\n"
            + "报销要求：需提供住宿发票与行程单，行程金额需与发票一致，并在十五个工作日内提交。";
    MockMultipartFile faq =
        new MockMultipartFile(
            "files", "faq.txt", "text/plain", paragraph.getBytes(StandardCharsets.UTF_8));
    MockMultipartFile ignored =
        new MockMultipartFile("files", "logo.png", "image/png", new byte[] {1, 2, 3});

    MvcResult uploaded =
        mvc.perform(
                multipart("/api/v1/cleaning/batches")
                    .file(faq)
                    .file(ignored)
                    .param("paths", "语料/faq.txt", "语料/logo.png")
                    .param("name", "集成测试语料")
                    .header("Idempotency-Key", "it-cleaning-upload-1")
                    .header("X-Dev-Subject", ADMIN)
                    .header("X-Dev-Name", "清洗集成测试管理员")
                    .with(csrf()))
            .andExpect(status().isAccepted())
            .andReturn();
    String uploadJson = body(uploaded);
    String batchId = JsonPath.read(uploadJson, "$.data.batchId");
    assertThat(JsonPath.<String>read(uploadJson, "$.data.status")).isEqualTo("QUEUED");
    assertThat(JsonPath.<Integer>read(uploadJson, "$.data.skippedCount")).isEqualTo(1);

    cleaningWorker.poll();

    MvcResult batchDetail =
        call(get("/api/v1/cleaning/batches/{batchId}", batchId))
            .andExpect(status().isOk())
            .andReturn();
    String batchJson = body(batchDetail);
    assertThat(JsonPath.<String>read(batchJson, "$.data.status")).isEqualTo("READY");
    assertThat(JsonPath.<Integer>read(batchJson, "$.data.fileCount")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(batchJson, "$.data.itemCount")).isEqualTo(1);
    assertThat(JsonPath.<String>read(batchJson, "$.data.sourceFiles[0].state")).isEqualTo("READY");

    MvcResult items =
        call(get("/api/v1/cleaning/items").param("status", "PENDING_REVIEW"))
            .andExpect(status().isOk())
            .andReturn();
    String itemsJson = body(items);
    assertThat(JsonPath.<Integer>read(itemsJson, "$.data.total")).isEqualTo(1);
    String itemId = JsonPath.read(itemsJson, "$.data.items[0].itemId");
    assertThat(JsonPath.<String>read(itemsJson, "$.data.items[0].scoreState"))
        .isEqualTo("UNAVAILABLE");
    assertThat(JsonPath.<String>read(itemsJson, "$.data.items[0].modelReason"))
        .contains("未启用模型打分");

    // 驳回必须填写原因。
    call(
            post("/api/v1/cleaning/items/review")
                .header("Idempotency-Key", "it-cleaning-review-0")
                .contentType("application/json")
                .content("{\"itemIds\":[\"%s\"],\"decision\":\"REJECT\"}".formatted(itemId)))
        .andExpect(status().isUnprocessableEntity());

    call(
            post("/api/v1/cleaning/items/review")
                .header("Idempotency-Key", "it-cleaning-review-1")
                .contentType("application/json")
                .content(
                    "{\"itemIds\":[\"%s\"],\"decision\":\"APPROVE\",\"reason\":\"表述完整\"}"
                        .formatted(itemId)))
        .andExpect(status().isOk());

    MvcResult promoted =
        call(
                post("/api/v1/cleaning/promotions")
                    .header("Idempotency-Key", "it-cleaning-promote-1")
                    .contentType("application/json")
                    .content(
                        """
                        {"itemIds":["%s"],"spaceId":"%s","documentName":"清洗知识-集成测试",
                         "policyState":"EFFECTIVE","effectiveFrom":"2026-01-01","reason":"集成测试流转"}
                        """
                            .formatted(itemId, spaceId)))
            .andExpect(status().isCreated())
            .andReturn();
    String documentVersionId = JsonPath.read(body(promoted), "$.data.documentVersionId");
    assertThat(JsonPath.<Integer>read(body(promoted), "$.data.promoted")).isEqualTo(1);

    // 生成的文档接入既有解析链路：解析后可切块，并已出现在空间草稿里。
    parseWorker.poll();
    String mediaType =
        jdbc.sql("SELECT media_type FROM document_version WHERE id = :id")
            .param("id", java.util.UUID.fromString(documentVersionId))
            .query(String.class)
            .single();
    assertThat(mediaType).isEqualTo("text/markdown");
    Long chunks =
        jdbc.sql("SELECT count(*) FROM document_chunk WHERE document_version_id = :id")
            .param("id", java.util.UUID.fromString(documentVersionId))
            .query(Long.class)
            .single();
    assertThat(chunks).isPositive();
    Long inDraft =
        jdbc.sql(
                "SELECT count(*) FROM space_draft_document WHERE document_version_id = :id")
            .param("id", java.util.UUID.fromString(documentVersionId))
            .query(Long.class)
            .single();
    assertThat(inDraft).isEqualTo(1);
    String itemStatus =
        jdbc.sql("SELECT status FROM cleaning_item WHERE id = :id")
            .param("id", java.util.UUID.fromString(itemId))
            .query(String.class)
            .single();
    assertThat(itemStatus).isEqualTo("PROMOTED");

    // 重复流转同一批条目应被拒绝（状态已变化）。
    call(
            post("/api/v1/cleaning/promotions")
                .header("Idempotency-Key", "it-cleaning-promote-2")
                .contentType("application/json")
                .content(
                    "{\"itemIds\":[\"%s\"],\"spaceId\":\"%s\"}".formatted(itemId, spaceId)))
        .andExpect(status().isConflict());
  }

  private ResultActions call(MockHttpServletRequestBuilder builder) throws Exception {
    return mvc.perform(
        builder
            .header("X-Dev-Subject", ADMIN)
            .header("X-Dev-Name", "清洗集成测试管理员")
            .with(csrf()));
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private static Path createTempRoot() {
    try {
      return Files.createTempDirectory("aiqa-cleaning-objects-");
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
