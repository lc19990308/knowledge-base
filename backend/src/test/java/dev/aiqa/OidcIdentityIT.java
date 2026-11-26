package dev.aiqa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.aiqa.testsupport.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifies the OIDC principal mapping and local account provisioning without a live identity
 * provider: the same (issuer, subject) always resolves to one account, and disabled users are
 * rejected on the next request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OidcIdentityIT {

  private static final TestDatabase DATABASE = TestDatabase.fromEnvironment().orElse(null);
  private static final String ISSUER = "https://idp.example.test/oidc";
  private static final String SUBJECT = "oidc-subject-1";

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
    registry.add("aiqa.worker.enabled", () -> "false");
    registry.add("aiqa.security.dev-header-auth.enabled", () -> "false");
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;

  @BeforeAll
  void requireDatabase() {
    Assumptions.assumeTrue(DATABASE != null, "未配置 IT_DB_* 测试数据库，跳过 PostgreSQL 集成测试");
  }

  @AfterAll
  void cleanUp() {
    if (DATABASE != null) {
      DATABASE.close();
    }
  }

  private MvcResult login() throws Exception {
    return mvc.perform(
            get("/api/v1/auth/me")
                .with(
                    oidcLogin()
                        .idToken(
                            token ->
                                token.issuer(ISSUER).subject(SUBJECT).claim("name", "OIDC 用户"))))
        .andExpect(status().isOk())
        .andReturn();
  }

  @Test
  void oidcPrincipalProvisioningIsStableAndDisabledUsersAreRejected() throws Exception {
    MvcResult first = login();
    String firstId = JsonPath.read(first.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.userId");
    assertThat(JsonPath.<java.util.List<String>>read(
            first.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.roles"))
        .containsExactly("USER");

    MvcResult second = login();
    String secondId = JsonPath.read(second.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.userId");
    assertThat(secondId).isEqualTo(firstId);
    assertThat(
            jdbc.sql("SELECT count(*) FROM app_user WHERE issuer = :issuer AND subject = :subject")
                .param("issuer", ISSUER)
                .param("subject", SUBJECT)
                .query(Long.class)
                .single())
        .isEqualTo(1);

    jdbc.sql("UPDATE app_user SET enabled = FALSE WHERE id = :id")
        .param("id", UUID.fromString(firstId))
        .update();
    mvc.perform(
            get("/api/v1/auth/me")
                .with(
                    oidcLogin()
                        .idToken(token -> token.issuer(ISSUER).subject(SUBJECT).claim("name", "OIDC 用户"))))
        .andExpect(status().isForbidden());
  }
}
