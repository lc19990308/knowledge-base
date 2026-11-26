package dev.aiqa;

import static dev.aiqa.testsupport.ApiResponseAssertions.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.aiqa.testsupport.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 列表接口的 offset/limit 分页与 total：条数由客户端指定、非法值按 400 拒绝、筛选后的 total 与
 * 服务端筛选一致，会话详情能补齐分页后不在当前页的会话。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ListPagingIT {

  private static final TestDatabase DATABASE = TestDatabase.fromEnvironment().orElse(null);
  private static final String ADMIN = "page-admin";
  private static final String READER = "page-reader";

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
    registry.add("aiqa.rate-limit.writes-per-minute", () -> "200");
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  private final List<String> spaceNames =
      List.of("分页空间-01", "分页空间-02", "分页空间-03", "分页空间-04", "分页空间-05");
  private String boundSpaceId = "";

  @BeforeAll
  void prepare() throws Exception {
    Assumptions.assumeTrue(DATABASE != null, "未配置 IT_DB_* 测试数据库，跳过 PostgreSQL 集成测试");
    for (String subject : List.of(ADMIN, READER)) {
      mvc.perform(get("/api/v1/auth/me").header("X-Dev-Subject", subject))
          .andExpect(status().isOk());
    }
    jdbc.sql(
            """
            UPDATE user_role SET role = 'SYSTEM_ADMIN'
            WHERE user_id = (SELECT id FROM app_user WHERE subject = :subject)
            """)
        .param("subject", ADMIN)
        .update();
    for (String name : spaceNames) {
      String created =
          body(
              mvc.perform(
                      post("/api/v1/spaces")
                          .header("X-Dev-Subject", ADMIN)
                          .with(csrf())
                          .contentType("application/json")
                          .content("{\"name\":\"" + name + "\",\"department\":\"分页测试部\"}"))
                  .andExpect(status().isCreated())
                  .andReturn());
      if (boundSpaceId.isEmpty()) {
        boundSpaceId = JsonPath.read(created, "$.data.spaceId");
      }
    }
  }

  @AfterAll
  void cleanUp() {
    if (DATABASE != null) {
      DATABASE.close();
    }
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private String page() throws Exception {
    return body(
        mvc.perform(get("/api/v1/spaces").header("X-Dev-Subject", ADMIN))
            .andExpect(status().isOk())
            .andReturn());
  }

  private String page(int limit, int offset) throws Exception {
    return body(
        mvc.perform(
                get("/api/v1/spaces")
                    .param("limit", Integer.toString(limit))
                    .param("offset", Integer.toString(offset))
                    .header("X-Dev-Subject", ADMIN))
            .andExpect(status().isOk())
            .andReturn());
  }

  private String pageBy(String name, String status, Integer limit) throws Exception {
    var request = get("/api/v1/spaces").header("X-Dev-Subject", ADMIN);
    if (name != null) {
      request = request.param("q", name);
    }
    if (status != null) {
      request = request.param("status", status);
    }
    if (limit != null) {
      request = request.param("limit", Integer.toString(limit));
    }
    return body(mvc.perform(request).andExpect(status().isOk()).andReturn());
  }

  @Test
  void limitSelectsPageSizeAndTotalCountsTheFilteredRows() throws Exception {
    String first = page(2, 0);
    assertThat(JsonPath.<List<Object>>read(first, "$.data.items")).hasSize(2);
    long total = JsonPath.<Number>read(first, "$.data.total").longValue();
    assertThat(total).isGreaterThanOrEqualTo(spaceNames.size());

    String second = page(2, 2);
    assertThat(JsonPath.<List<Object>>read(second, "$.data.items")).hasSize(2);
    assertThat(JsonPath.<Number>read(second, "$.data.total").longValue()).isEqualTo(total);
    assertThat(JsonPath.<List<String>>read(second, "$.data.items[*].spaceId"))
        .doesNotContainAnyElementsOf(JsonPath.read(first, "$.data.items[*].spaceId"));

    // 省略 limit 时与分页之前的响应一致：返回全部记录，total 等于条数。
    String allPage = page();
    int allCount = JsonPath.<List<Object>>read(allPage, "$.data.items").size();
    assertThat(JsonPath.<Number>read(allPage, "$.data.total").longValue()).isEqualTo(allCount);

    // offset 越过 total 时返回空页，total 不变。
    String beyond = page(2, (int) total + 10);
    assertThat(JsonPath.<List<Object>>read(beyond, "$.data.items")).isEmpty();
    assertThat(JsonPath.<Number>read(beyond, "$.data.total").longValue()).isEqualTo(total);
  }

  @Test
  void nameAndStatusFiltersRunOnTheServer() throws Exception {
    String byName = pageBy("分页空间-0", null, null);
    assertThat(JsonPath.<List<String>>read(byName, "$.data.items[*].name"))
        .hasSize(spaceNames.size())
        .allMatch(name -> name.startsWith("分页空间-0"));

    // 新建空间都没有发布内容，DRAFT 与 PUBLISHED 的 total 相加等于未筛选总数。
    long draft = JsonPath.<Number>read(pageBy(null, "DRAFT", null), "$.data.total").longValue();
    long published =
        JsonPath.<Number>read(pageBy(null, "PUBLISHED", null), "$.data.total").longValue();
    long all = JsonPath.<Number>read(page(), "$.data.total").longValue();
    assertThat(draft + published).isEqualTo(all);
    assertThat(JsonPath.<Number>read(pageBy(null, "REVIEWING", null), "$.data.total").longValue())
        .isZero();

    String filtered = pageBy("分页空间-01", null, 1);
    assertThat(JsonPath.<List<String>>read(filtered, "$.data.items[*].name"))
        .containsExactly("分页空间-01");
    assertThat(JsonPath.<Number>read(filtered, "$.data.total").longValue()).isEqualTo(1);
  }

  @Test
  void invalidPagingAndFilterValuesAreRejected() throws Exception {
    for (var bad : List.of(
        new String[] {"limit", "0"},
        new String[] {"limit", "201"},
        new String[] {"offset", "-1"},
        new String[] {"status", "NOPE"})) {
      mvc.perform(
              get("/api/v1/spaces").param(bad[0], bad[1]).header("X-Dev-Subject", ADMIN))
          .andExpect(status().isBadRequest())
          .andExpect(problem("INVALID_REQUEST"));
    }
  }

  @Test
  void templateConfigAndUserListsAcceptLimitAndReportTotal() throws Exception {
    String templates =
        body(
            mvc.perform(
                    get("/api/v1/answer-templates?limit=1").header("X-Dev-Subject", ADMIN))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(JsonPath.<List<Object>>read(templates, "$.data.items").size())
        .isLessThanOrEqualTo(1);
    assertThat(JsonPath.<Number>read(templates, "$.data.total").longValue())
        .isGreaterThanOrEqualTo(
            (long) JsonPath.<List<Object>>read(templates, "$.data.items").size());

    String configs =
        body(
            mvc.perform(get("/api/v1/model-configs?limit=1").header("X-Dev-Subject", ADMIN))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(JsonPath.<Object>read(configs, "$.data.total")).isNotNull();

    String users =
        body(
            mvc.perform(get("/api/v1/users?limit=1").header("X-Dev-Subject", ADMIN))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(JsonPath.<List<Object>>read(users, "$.data.items").size())
        .isLessThanOrEqualTo(1);
    assertThat(JsonPath.<Number>read(users, "$.data.total").longValue()).isGreaterThan(0);
  }

  @Test
  void assistantsAndConversationsPageWithTotal() throws Exception {
    String createdAssistant =
        body(
            mvc.perform(
                    post("/api/v1/assistants")
                        .header("X-Dev-Subject", ADMIN)
                        .header("Idempotency-Key", "page-assistant-1")
                        .with(csrf())
                        .contentType("application/json")
                        .content(
                            "{\"name\":\"分页助手\",\"bindings\":[{\"spaceId\":\"%s\",\"role\":\"RETRIEVAL\"}]}"
                                .formatted(boundSpaceId)))
                .andExpect(status().isCreated())
                .andReturn());
    String assistantId = JsonPath.read(createdAssistant, "$.data.assistantId");

    // 管理模式与「助手配置」页面一致：不受发布状态影响，能看到刚建的助手。
    String assistants =
        body(
            mvc.perform(
                    get("/api/v1/assistants")
                        .param("management", "true")
                        .param("limit", "1")
                        .header("X-Dev-Subject", ADMIN))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(JsonPath.<List<Object>>read(assistants, "$.data.items").size())
        .isLessThanOrEqualTo(1);
    assertThat(JsonPath.<Number>read(assistants, "$.data.total").longValue()).isPositive();

    String conversation =
        body(
            mvc.perform(
                    post("/api/v1/conversations")
                        .header("X-Dev-Subject", ADMIN)
                        .header("Idempotency-Key", "page-conv-1")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"assistantId\":\"" + assistantId + "\",\"title\":\"分页会话\"}"))
                .andExpect(status().isCreated())
                .andReturn());
    String conversationId = JsonPath.read(conversation, "$.data.conversationId");

    String listed =
        body(
            mvc.perform(get("/api/v1/conversations?limit=1").header("X-Dev-Subject", ADMIN))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(JsonPath.<Object>read(listed, "$.data.total")).isNotNull();

    // 会话详情：分页后侧栏当前选中项可能不在本页，靠它补齐标题与归属助手。
    String detail =
        body(
            mvc.perform(
                    get("/api/v1/conversations/" + conversationId)
                        .header("X-Dev-Subject", ADMIN))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(JsonPath.<String>read(detail, "$.data.title")).isEqualTo("分页会话");
    assertThat(JsonPath.<String>read(detail, "$.data.conversationId")).isEqualTo(conversationId);

    mvc.perform(
            get("/api/v1/conversations/" + UUID.randomUUID()).header("X-Dev-Subject", ADMIN))
        .andExpect(status().isNotFound());
  }

  @Test
  void publishRequestsFilterByStatusAndPageWithTotal() throws Exception {
    String pending =
        body(
            mvc.perform(
                    get("/api/v1/publish-requests?status=PENDING_REVIEW&limit=1")
                        .header("X-Dev-Subject", ADMIN))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(JsonPath.<List<String>>read(pending, "$.data.items[*].status"))
        .allMatch("PENDING_REVIEW"::equals);

    String page =
        body(
            mvc.perform(get("/api/v1/publish-requests?limit=1").header("X-Dev-Subject", ADMIN))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(JsonPath.<Number>read(page, "$.data.total").longValue())
        .isGreaterThanOrEqualTo(JsonPath.<Number>read(pending, "$.data.total").longValue());

    mvc.perform(
            get("/api/v1/publish-requests?status=NOT_A_STATUS").header("X-Dev-Subject", ADMIN))
        .andExpect(status().isBadRequest())
        .andExpect(problem("INVALID_REQUEST"));
  }
}
