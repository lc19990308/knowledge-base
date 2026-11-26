package dev.aiqa;

import static dev.aiqa.testsupport.ApiResponseAssertions.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.aiqa.testsupport.TestDatabase;
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

/** 全局模型配置（多条）：CRUD、别名唯一、默认指针保护与解析。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelConfigIT {

  private static final dev.aiqa.testsupport.TestDatabase DATABASE =
      dev.aiqa.testsupport.TestDatabase.fromEnvironment().orElse(null);
  private static final String ADMIN = "mc-admin";
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
      return Files.createTempDirectory("aiqa-objects-mc-");
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  @BeforeAll
  void requireDatabase() throws Exception {
    Assumptions.assumeTrue(DATABASE != null, "未配置 IT_DB_* 测试数据库，跳过 PostgreSQL 集成测试");
    mvc.perform(get("/api/v1/auth/me").header("X-Dev-Subject", ADMIN)).andReturn();
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

  private MvcResult callAs(String subject, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
      throws Exception {
    return mvc.perform(builder.header("X-Dev-Subject", subject).with(csrf())).andReturn();
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  void modelConfigsSupportMultipleEntriesAndDefaultPointer() throws Exception {
    MvcResult first =
        callAs(ADMIN, post("/api/v1/model-configs")
                .header("Idempotency-Key", "mc-create-1")
                .contentType("application/json")
                .content("{\"alias\":\"GLM-5.3\",\"credentialRef\":\"vault:glm53\",\"enabled\":true,\"protocol\":\"openai_compatible\",\"baseUrl\":\"https://example.test/v1\",\"modelName\":\"glm-5.3\"}"));
    assertThat(first.getResponse().getStatus()).isEqualTo(201);
    String modelA = JsonPath.read(body(first), "$.data.id");

    MvcResult second =
        callAs(ADMIN, post("/api/v1/model-configs")
                .header("Idempotency-Key", "mc-create-2")
                .contentType("application/json")
                .content("{\"alias\":\"GLM-5.3-Flash\",\"credentialRef\":\"vault:flash\",\"enabled\":true,\"protocol\":\"openai_compatible\",\"baseUrl\":\"https://example.test/v1\",\"modelName\":\"glm-5.3-flash\"}"));
    assertThat(second.getResponse().getStatus()).isEqualTo(201);

    MvcResult replay =
        callAs(ADMIN, post("/api/v1/model-configs")
                .header("Idempotency-Key", "mc-create-1")
                .contentType("application/json")
                .content("{\"alias\":\"GLM-5.3\",\"credentialRef\":\"vault:glm53\",\"enabled\":true,\"protocol\":\"openai_compatible\",\"baseUrl\":\"https://example.test/v1\",\"modelName\":\"glm-5.3\"}"));
    assertThat(JsonPath.<String>read(body(replay), "$.data.id")).isEqualTo(modelA);

    MvcResult duplicate =
        callAs(ADMIN, post("/api/v1/model-configs")
                .header("Idempotency-Key", "mc-create-3")
                .contentType("application/json")
                .content("{\"alias\":\"GLM-5.3\",\"credentialRef\":\"vault:x\",\"enabled\":true,\"protocol\":\"openai_compatible\",\"baseUrl\":\"https://example.test/v1\",\"modelName\":\"glm-5.3\"}"));
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);

    String firstEtag = first.getResponse().getHeader("ETag");
    MvcResult updated =
        callAs(ADMIN, put("/api/v1/model-configs/{id}", modelA)
                .header("If-Match", firstEtag)
                .header("Idempotency-Key", "mc-update-1")
                .contentType("application/json")
                .content("{\"alias\":\"GLM-5.3-生产\",\"credentialRef\":\"vault:glm53\",\"enabled\":true,\"protocol\":\"anthropic\",\"baseUrl\":\"https://example.test/v1\",\"modelName\":\"claude-sonnet\"}"));
    assertThat(updated.getResponse().getStatus()).isEqualTo(200);
    assertThat(JsonPath.<String>read(body(updated), "$.data.alias")).isEqualTo("GLM-5.3-生产");

    MvcResult list = callAs(ADMIN, get("/api/v1/model-configs"));
    assertThat(list.getResponse().getStatus()).isEqualTo(200);
    assertThat(JsonPath.<List<String>>read(body(list), "$.data.items[*].alias"))
        .contains("GLM-5.3-生产", "GLM-5.3-Flash");

    MvcResult settingsGet = callAs(ADMIN, get("/api/v1/settings/global"));
    String settingsEtag = settingsGet.getResponse().getHeader("ETag");
    MvcResult settingsPut =
        callAs(ADMIN, put("/api/v1/settings/global")
                .header("If-Match", settingsEtag)
                .header("Idempotency-Key", "mc-settings-1")
                .contentType("application/json")
                .content(("{\"defaultModelId\":\"%s\",\"templateId\":null,"
                    + "\"contextLimit\":12,\"usageReportEnabled\":true}").formatted(modelA)));
    assertThat(settingsPut.getResponse().getStatus()).isEqualTo(200);
    assertThat(settingsPut.getResponse().getHeader("ETag")).isNotBlank().isNotEqualTo(settingsEtag);
    assertThat(JsonPath.<String>read(body(settingsPut), "$.data.modelAlias")).isEqualTo("GLM-5.3-生产");
    assertThat(JsonPath.<String>read(body(settingsPut), "$.data.defaultModelId")).isEqualTo(modelA);

    MvcResult deleteDefault =
        callAs(ADMIN, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/model-configs/{id}", modelA)
                .header("If-Match", updated.getResponse().getHeader("ETag"))
                .header("Idempotency-Key", "mc-delete-1"));
    assertThat(deleteDefault.getResponse().getStatus()).isEqualTo(409);

    MvcResult stale =
        callAs(ADMIN, put("/api/v1/model-configs/{id}", modelA)
                .header("If-Match", firstEtag)
                .header("Idempotency-Key", "mc-update-2")
                .contentType("application/json")
                .content("{\"alias\":\"GLM-5.3-又改\",\"credentialRef\":\"vault:glm53\",\"enabled\":true,\"protocol\":\"openai_compatible\",\"baseUrl\":\"https://example.test/v1\",\"modelName\":\"glm-5.3\"}"));
    assertThat(stale.getResponse().getStatus()).isEqualTo(412);
    problem("ETAG_MISMATCH").match(stale);

    MvcResult staleSettings =
        callAs(ADMIN, put("/api/v1/settings/global")
                .header("If-Match", settingsEtag)
                .header("Idempotency-Key", "mc-settings-stale")
                .contentType("application/json")
                .content(("{\"defaultModelId\":\"%s\",\"templateId\":null,"
                    + "\"contextLimit\":12,\"usageReportEnabled\":true}").formatted(modelA)));
    assertThat(staleSettings.getResponse().getStatus()).isEqualTo(412);
    problem("ETAG_MISMATCH").match(staleSettings);
  }
}
