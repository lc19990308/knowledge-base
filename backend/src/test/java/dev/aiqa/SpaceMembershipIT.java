package dev.aiqa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.aiqa.testsupport.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 空间授权的两条规则：系统管理员默认拥有全部空间的读取与维护权限；管理员可以一次给普通成员分配
 * 多个空间，提交是原子的全量覆盖，且不会收回成员在自己创建的空间上的权限。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpaceMembershipIT {

  private static final TestDatabase DATABASE = TestDatabase.fromEnvironment().orElse(null);
  private static final String ADMIN = "mem-admin";
  private static final String OWNER = "mem-owner";
  private static final String MEMBER = "mem-member";
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
      return Files.createTempDirectory("aiqa-objects-members-");
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

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
    callAs(OWNER, get("/api/v1/auth/me")).andExpect(status().isOk());
    jdbc.sql("UPDATE app_user SET can_create_space = TRUE WHERE subject = :subject")
        .param("subject", OWNER)
        .update();
    callAs(MEMBER, get("/api/v1/auth/me")).andExpect(status().isOk());
  }

  @AfterAll
  void cleanUp() {
    if (DATABASE != null) {
      DATABASE.close();
    }
  }

  private ResultActions callAs(String subject, MockHttpServletRequestBuilder builder)
      throws Exception {
    return mvc.perform(builder.header("X-Dev-Subject", subject).with(csrf()));
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private String userIdOf(String subject) {
    return jdbc.sql("SELECT id FROM app_user WHERE subject = :subject")
        .param("subject", subject)
        .query(UUID.class)
        .single()
        .toString();
  }

  private String createSpaceAs(String subject, String name) throws Exception {
    MvcResult result =
        callAs(
                subject,
                post("/api/v1/spaces")
                    .contentType("application/json")
                    .content("{\"name\":\"" + name + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    return JsonPath.read(body(result), "$.data.spaceId");
  }

  private ResultActions putMemberships(String userId, String memberships) throws Exception {
    return callAs(
        ADMIN,
        put("/api/v1/users/{userId}/space-memberships", userId)
            .contentType("application/json")
            .content("{\"memberships\":" + memberships + "}"));
  }

  @Test
  void adminHasFullAccessToEverySpaceWithoutMembershipRows() throws Exception {
    String spaceId = createSpaceAs(OWNER, "管理员全权空间");
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM space_member
                    WHERE space_id = :space
                      AND user_id = (SELECT id FROM app_user WHERE subject = :subject)
                    """)
                .param("space", UUID.fromString(spaceId))
                .param("subject", ADMIN)
                .query(Long.class)
                .single())
        .isZero();

    callAs(ADMIN, get("/api/v1/spaces/{spaceId}", spaceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.canRead").value(true))
        .andExpect(jsonPath("$.data.canMaintain").value(true));
    // 维护入口不再因为缺少成员记录而拒绝管理员。
    callAs(ADMIN, get("/api/v1/spaces/{spaceId}/draft", spaceId)).andExpect(status().isOk());

    MvcResult result = callAs(ADMIN, get("/api/v1/spaces")).andExpect(status().isOk()).andReturn();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = JsonPath.read(body(result), "$.data.items");
    assertThat(items).isNotEmpty();
    assertThat(items)
        .allSatisfy(
            item -> {
              assertThat(item.get("canRead")).isEqualTo(Boolean.TRUE);
              assertThat(item.get("canMaintain")).isEqualTo(Boolean.TRUE);
            });
  }

  @Test
  void bulkAssignmentCoversMultipleSpacesAtomically() throws Exception {
    String spaceA = createSpaceAs(OWNER, "批量空间 A");
    String spaceB = createSpaceAs(OWNER, "批量空间 B");
    String memberId = userIdOf(MEMBER);

    putMemberships(
            memberId,
            """
            [{"spaceId":"%s","canRead":true,"canMaintain":false},
             {"spaceId":"%s","canRead":true,"canMaintain":true}]
            """
                .formatted(spaceA, spaceB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(2));

    callAs(MEMBER, get("/api/v1/spaces/{spaceId}", spaceA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.canRead").value(true))
        .andExpect(jsonPath("$.data.canMaintain").value(false));
    callAs(MEMBER, get("/api/v1/spaces/{spaceId}", spaceB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.canMaintain").value(true));

    // 未列出的空间撤销授权：只保留 B。
    putMemberships(
            memberId, """
            [{"spaceId":"%s","canRead":true,"canMaintain":true}]
            """
                .formatted(spaceB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].spaceId").value(spaceB));
    callAs(MEMBER, get("/api/v1/spaces/{spaceId}", spaceA)).andExpect(status().isNotFound());
    assertThat(
            jdbc.sql("SELECT count(*) FROM space_member WHERE user_id = :user")
                .param("user", UUID.fromString(memberId))
                .query(Long.class)
                .single())
        .isEqualTo(1);

    putMemberships(memberId, "[]").andExpect(status().isOk());
    assertThat(
            jdbc.sql("SELECT count(*) FROM space_member WHERE user_id = :user")
                .param("user", UUID.fromString(memberId))
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void rejectedBatchLeavesExistingGrantsUntouched() throws Exception {
    String space = createSpaceAs(OWNER, "事务校验空间");
    String memberId = userIdOf(MEMBER);
    putMemberships(
            memberId,
            """
            [{"spaceId":"%s","canRead":true,"canMaintain":true}]
            """
                .formatted(space))
        .andExpect(status().isOk());

    putMemberships(
            memberId,
            """
            [{"spaceId":"%s","canRead":true,"canMaintain":false},
             {"spaceId":"%s","canRead":true,"canMaintain":false}]
            """
                .formatted(space, UUID.randomUUID()))
        .andExpect(status().isNotFound());

    callAs(MEMBER, get("/api/v1/spaces/{spaceId}", space))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.canMaintain").value(true));
  }

  @Test
  void ownerKeepsOwnSpaceWhenMembershipsAreCleared() throws Exception {
    String space = createSpaceAs(OWNER, "所有者空间");
    String ownerId = userIdOf(OWNER);

    putMemberships(ownerId, "[]").andExpect(status().isOk());

    callAs(OWNER, get("/api/v1/spaces/{spaceId}", space))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.canRead").value(true))
        .andExpect(jsonPath("$.data.canMaintain").value(true));
    callAs(OWNER, get("/api/v1/spaces/{spaceId}/draft", space)).andExpect(status().isOk());
  }

  @Test
  void nonAdminCannotReadOrReplaceSpaceMemberships() throws Exception {
    String memberId = userIdOf(MEMBER);
    callAs(MEMBER, get("/api/v1/users/{userId}/space-memberships", memberId))
        .andExpect(status().isForbidden());
    callAs(
            MEMBER,
            put("/api/v1/users/{userId}/space-memberships", memberId)
                .contentType("application/json")
                .content("{\"memberships\":[]}"))
        .andExpect(status().isForbidden());
  }
}
