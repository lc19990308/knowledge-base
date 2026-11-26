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
import dev.aiqa.worker.DocumentParseWorker;
import dev.aiqa.worker.PublishWorker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** Concurrency: one winner per decision; and approval waits for indexing instead of leaking. */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrencyAndWaitingIndexIT {

  private static final TestDatabase DATABASE = TestDatabase.fromEnvironment().orElse(null);
  private static final String ADMIN = "conc-admin";
  private static final String READER = "conc-reader";
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
      return Files.createTempDirectory("aiqa-objects4-");
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private DocumentParseWorker parseWorker;
  @Autowired private PublishWorker publishWorker;

  @BeforeAll
  void requireDatabase() throws Exception {
    Assumptions.assumeTrue(DATABASE != null, "未配置 IT_DB_* 测试数据库，跳过 PostgreSQL 集成测试");
    mvc.perform(get("/api/v1/auth/me").header("X-Dev-Subject", ADMIN)).andExpect(status().isOk());
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

  private org.springframework.test.web.servlet.ResultActions call(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) throws Exception {
    return mvc.perform(builder.header("X-Dev-Subject", ADMIN).with(csrf()));
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void concurrentApprovalsProduceOnePublicationAndReviewWaitsForIndex() throws Exception {
    String spaceId =
        JsonPath.read(
            body(
                call(post("/api/v1/spaces")
                        .contentType("application/json")
                        .content("{\"name\":\"并发审核测试库\"}"))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.spaceId");

    MockMultipartFile document =
        new MockMultipartFile("files", "并发表.md", "text/markdown",
            "并发场景下的制度正文：十五个工作日。".getBytes(StandardCharsets.UTF_8));
    MvcResult imported =
        mvc.perform(multipart("/api/v1/spaces/{spaceId}/imports", spaceId)
                .file(document)
                .header("X-Dev-Subject", ADMIN)
                .header("Idempotency-Key", "conc-import-1")
                .with(csrf()))
            .andExpect(status().isAccepted())
            .andReturn();
    String docId = JsonPath.read(body(imported), "$.data.files[0].documentVersionId");

    // Approve before the parse job runs: the request must wait, never expose the document.
    String draftEtag =
        call(get("/api/v1/spaces/{spaceId}/draft", spaceId)).andReturn().getResponse().getHeader("ETag");
    String savedEtag =
        call(put("/api/v1/spaces/{spaceId}/draft", spaceId)
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
                call(post("/api/v1/spaces/{spaceId}/publish-requests", spaceId)
                        .header("If-Match", savedEtag)
                        .header("Idempotency-Key", "conc-submit-1")
                        .contentType("application/json")
                        .content("{\"reason\":\"并发测试\"}"))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.requestId");
    String requestEtag =
        call(get("/api/v1/publish-requests/{requestId}", requestId))
            .andReturn()
            .getResponse()
            .getHeader("ETag");
    MvcResult firstApproval =
        call(post("/api/v1/publish-requests/{requestId}/decision", requestId)
                .header("If-Match", requestEtag)
                .header("Idempotency-Key", "conc-decide-1")
                .contentType("application/json")
                .content("{\"decision\":\"APPROVE\"}"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(JsonPath.<String>read(body(firstApproval), "$.data.status")).isEqualTo("APPROVED_WAITING_INDEX");
    assertThat(
            jdbc.sql("SELECT current_publication_id FROM space WHERE id = :id")
                .param("id", UUID.fromString(spaceId))
                .query(UUID.class)
                .optional())
        .isEmpty();
    // 等待索引期间员工侧仍不可见（成员也不行）；系统管理员按草稿来源可检索。
    mvc.perform(get("/api/v1/auth/me").header("X-Dev-Subject", READER))
        .andExpect(status().isOk());
    jdbc.sql(
            """
            INSERT INTO space_member (space_id, user_id, can_read, can_maintain, granted_by)
            SELECT :space, id, true, false, id FROM app_user WHERE subject = :subject
            """)
        .param("space", UUID.fromString(spaceId))
        .param("subject", READER)
        .update();
    mvc.perform(get("/api/v1/spaces/{spaceId}/search", spaceId)
            .header("X-Dev-Subject", READER)
            .param("q", "十五个工作日"))
        .andExpect(status().isNotFound());
    assertThat(
            JsonPath.<String>read(
                body(
                    call(get("/api/v1/spaces/{spaceId}/search", spaceId).param("q", "十五个工作日"))
                        .andExpect(status().isOk())
                        .andReturn()),
                "$.data.contentSource"))
        .isEqualTo("DRAFT");

    // Once indexing completes the worker switches the pointer exactly once.
    parseWorker.poll();
    publishWorker.poll();
    assertThat(
            jdbc.sql("SELECT status FROM publish_request WHERE id = :id")
                .param("id", UUID.fromString(requestId))
                .query(String.class)
                .single())
        .isEqualTo("PUBLISHED");
    long publications =
        jdbc.sql("SELECT count(*) FROM space_publication WHERE space_id = :id")
            .param("id", UUID.fromString(spaceId))
            .query(Long.class)
            .single();
    assertThat(publications).isEqualTo(1);

    // Two concurrent withdrawals with the same expectation: exactly one wins.
    UUID publicationId =
        jdbc.sql("SELECT current_publication_id FROM space WHERE id = :id")
            .param("id", UUID.fromString(spaceId))
            .query(UUID.class)
            .single();
    String reviewer = "conc-withdraw-admin";
    mvc.perform(get("/api/v1/auth/me").header("X-Dev-Subject", reviewer))
        .andExpect(status().isOk());
    jdbc.sql("UPDATE user_role SET role = 'SYSTEM_ADMIN' WHERE user_id = (SELECT id FROM app_user WHERE subject = :subject)")
        .param("subject", reviewer).update();
    mvc.perform(get("/api/v1/spaces").header("X-Dev-Subject", reviewer))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .jsonPath("$.data.items[0].spaceId").value(spaceId));

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int index = 0; index < 2; index++) {
      String key = "conc-withdraw-" + index;
      tasks.add(() -> {
        start.await();
        return mvc.perform(post("/api/v1/spaces/{spaceId}/withdrawals", spaceId)
                 .header("X-Dev-Subject", reviewer).with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content("{\"expectedPublicationId\":\"%s\",\"reason\":\"并发撤回\"}".formatted(publicationId)))
            .andReturn()
            .getResponse()
            .getStatus();
      });
    }
    List<Future<Integer>> futures = new ArrayList<>();
    for (Callable<Integer> task : tasks) {
      futures.add(pool.submit(task));
    }
    start.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> future : futures) {
      statuses.add(future.get());
    }
    pool.shutdownNow();
    assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    assertThat(
            jdbc.sql("SELECT publication_epoch FROM space WHERE id = :id")
                .param("id", UUID.fromString(spaceId))
                .query(Long.class)
                .single())
        .isEqualTo(2L);
  }
}
