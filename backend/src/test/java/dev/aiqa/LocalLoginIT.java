package dev.aiqa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 本地账号体系端到端：管理员建号 → 登录 → 停用 → 限流 → 改密。
 * 引导账号（admin/user）在各 IT 上下文中同样会被创建，正好用于登录断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalLoginIT {

  private static final dev.aiqa.testsupport.TestDatabase DATABASE =
      dev.aiqa.testsupport.TestDatabase.fromEnvironment().orElse(null);
  private static final String ADMIN = "acc-admin";
  /** 集成测试固定验证码：每个 captchaId 只能用一次，所以每次登录前都要重新取一枚。 */
  private static final String CAPTCHA = "K7QM";
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
    registry.add("aiqa.login.max-failed-attempts", () -> "3");
    // 验证码固定为 K7QM，测试才能在不知道随机字的前提下走完登录；生产不设置该属性。
    registry.add("aiqa.login.captcha.fixed-code", () -> CAPTCHA);
  }

  private static Path tempRoot() {
    try {
      return Files.createTempDirectory("aiqa-objects-login-");
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  @BeforeAll
  void requireDatabaseAndAdmin() throws Exception {
    Assumptions.assumeTrue(DATABASE != null, "未配置 IT_DB_* 测试数据库，跳过 PostgreSQL 集成测试");
    // 引导账号已带 SYSTEM_ADMIN；幂等补齐，避免主键冲突。
    jdbc.sql(
            """
            INSERT INTO user_role (user_id, role)
            SELECT id, 'SYSTEM_ADMIN' FROM app_user WHERE issuer = 'local' AND subject = 'admin'
            ON CONFLICT (user_id, role) DO NOTHING
            """)
        .update();
    // 先请求一次让 dev 头身份落库，再授角色。
    mvc.perform(get("/api/v1/auth/me").header("X-Dev-Subject", ADMIN)).andReturn();
    // dev 头身份同样授系统管理员，供管理接口使用。
    jdbc.sql(
            """
            INSERT INTO user_role (user_id, role)
            SELECT id, 'SYSTEM_ADMIN' FROM app_user WHERE issuer = 'https://dev.local/oidc'
              AND subject = :subject
            ON CONFLICT (user_id, role) DO NOTHING
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

  private MvcResult callAs(String subject, MockHttpServletRequestBuilder builder) throws Exception {
    return mvc.perform(builder.header("X-Dev-Subject", subject).with(csrf())).andReturn();
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  /**
   * 组装一次带验证码的登录请求：先 GET /auth/captcha 取 captchaId，再回传固定字符。
   * 验证码是一次性的，所以不能用常量 id 复用。
   */
  private MockHttpServletRequestBuilder login(String username, String password) throws Exception {
    MvcResult challenge = mvc.perform(get("/api/v1/auth/captcha")).andReturn();
    String captchaId = JsonPath.read(body(challenge), "$.data.captchaId");
    return post("/api/v1/auth/login")
        .contentType("application/json")
        .content(
            ("{\"username\":\"%s\",\"password\":\"%s\",\"captchaId\":\"%s\","
                    + "\"captchaCode\":\"%s\"}")
                .formatted(username, password, captchaId, CAPTCHA));
  }

  @Test
  void loginRequiresCaptchaAndConsumesItOnce() throws Exception {
    // 缺验证码字段：400 INVALID_CAPTCHA，密码对错都不参与判断。
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_CAPTCHA"));

    // 字符错误：400，同时该题作废。
    MvcResult challenge = mvc.perform(get("/api/v1/auth/captcha")).andReturn();
    assertThat(challenge.getResponse().getStatus()).isEqualTo(200);
    String captchaId = JsonPath.read(body(challenge), "$.data.captchaId");
    assertThat(JsonPath.<String>read(body(challenge), "$.data.image"))
        .startsWith("data:image/png;base64,");
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content(
                    ("{\"username\":\"admin\",\"password\":\"admin123\","
                            + "\"captchaId\":\"%s\",\"captchaCode\":\"WRNG\"}")
                        .formatted(captchaId)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_CAPTCHA"));
    // 同一 captchaId 再用正确字符也拒绝：一次性。
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content(
                    ("{\"username\":\"admin\",\"password\":\"admin123\","
                            + "\"captchaId\":\"%s\",\"captchaCode\":\"%s\"}")
                        .formatted(captchaId, CAPTCHA)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_CAPTCHA"));

    // 重新取题 + 正确字符 + 正确密码：登录成功。
    mvc.perform(login("admin", "admin123").with(csrf())).andExpect(status().isOk());
  }

  @Test
  void localAccountsLogInManageAndAreRateLimited() throws Exception {
    // 引导账号在首次启动时创建：admin 是系统管理员。
    MvcResult meResult = mvc.perform(login("admin", "admin123").with(csrf())).andReturn();
    assertThat(meResult.getResponse().getStatus()).isEqualTo(200);
    String me = body(meResult);
    assertThat(JsonPath.<List<String>>read(me, "$.data.roles")).contains("SYSTEM_ADMIN");

    // 错误密码统一 401，不区分用户名是否存在。
    mvc.perform(login("admin", "wrong-password").with(csrf()))
        .andExpect(status().isUnauthorized());
    mvc.perform(login("nobody-here", "whatever123").with(csrf()))
        .andExpect(status().isUnauthorized());

    // 管理员建号 → 新账号可登录，且是普通用户。
    MvcResult createdResult =
        callAs(
            ADMIN,
            post("/api/v1/users")
                .contentType("application/json")
                .content(
                    "{\"username\":\"clerk01\",\"displayName\":\"受理员小张\","
                        + "\"password\":\"s3cure-pass\",\"roles\":[\"USER\"]}"));
    assertThat(createdResult.getResponse().getStatus()).isEqualTo(201);
    String created = body(createdResult);
    String clerkId = JsonPath.read(created, "$.data.userId");
    assertThat(JsonPath.<String>read(created, "$.data.issuer")).isEqualTo("local");

    MvcResult clerkResult = mvc.perform(login("clerk01", "s3cure-pass").with(csrf())).andReturn();
    assertThat(clerkResult.getResponse().getStatus()).isEqualTo(200);
    String clerkMe = body(clerkResult);
    assertThat(JsonPath.<List<String>>read(clerkMe, "$.data.roles")).containsExactly("USER");

    // 重名 409。
    org.springframework.test.web.servlet.MvcResult duplicate =
        callAs(ADMIN, post("/api/v1/users")
                .contentType("application/json")
                .content(
                    "{\"username\":\"clerk01\",\"displayName\":\"重复\",\"password\":\"s3cure-pass\"}"));
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);

    // 停用后登录 403。
    String accessEtag =
        mvc.perform(get("/api/v1/users/{id}/access", clerkId).header("X-Dev-Subject", ADMIN))
            .andReturn()
            .getResponse()
            .getHeader("ETag");
    mvc.perform(
            put("/api/v1/users/{id}/access", clerkId)
                .header("X-Dev-Subject", ADMIN)
                .header("If-Match", accessEtag)
                .with(csrf())
                .contentType("application/json")
                .content("{\"enabled\":false,\"roles\":[\"USER\"],\"canCreateSpace\":false}"))
        .andExpect(status().isOk());
    mvc.perform(login("clerk01", "s3cure-pass").with(csrf())).andExpect(status().isForbidden());
    mvc.perform(
            put("/api/v1/users/{id}/access", clerkId)
                .header("X-Dev-Subject", ADMIN)
                .header("If-Match", accessEtag)
                .with(csrf())
                .contentType("application/json")
                .content("{\"enabled\":true,\"roles\":[\"USER\"],\"canCreateSpace\":false}"))
        .andExpect(status().isOk());

    // 限流：连续失败达到阈值后 429，正确密码也被拦；窗口随新用户名重置。
    for (int attempt = 0; attempt < 3; attempt++) {
      mvc.perform(login("ratelimit01", "limit-pass-77").with(csrf())).andExpect(status().isUnauthorized());
    }
    mvc.perform(login("ratelimit01", "limit-pass-77").with(csrf())).andExpect(status().isTooManyRequests());

    // 管理员改密 → 旧密码 401，新密码 200。
    mvc.perform(
            post("/api/v1/users/{id}/credential", clerkId)
                .header("X-Dev-Subject", ADMIN)
                .with(csrf())
                .contentType("application/json")
                .content("{\"password\":\"fresh-pass-99\"}"))
        .andExpect(status().isNoContent());
    mvc.perform(login("clerk01", "s3cure-pass").with(csrf())).andExpect(status().isUnauthorized());
    mvc.perform(login("clerk01", "fresh-pass-99").with(csrf())).andExpect(status().isOk());
  }
}
