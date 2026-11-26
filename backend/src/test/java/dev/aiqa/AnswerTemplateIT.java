package dev.aiqa;

import static dev.aiqa.testsupport.ApiResponseAssertions.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.aiqa.testsupport.TestDatabase;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Answer templates as first-class entities: create, read, update, delete, plus the reference rules
 * that keep assistant and global settings pointing at one source.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnswerTemplateIT {

  private static final TestDatabase DATABASE = TestDatabase.fromEnvironment().orElse(null);
  private static final String ADMIN = "tpl-admin";
  private static final String READER = "tpl-reader";

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

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void templatesAreReusableEntitiesWithReferenceGuards() throws Exception {
    assertThat(
            mvc.perform(get("/api/v1/answer-templates").header("X-Dev-Subject", READER))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);

    MvcResult created =
        mvc.perform(
                post("/api/v1/answer-templates")
                    .header("X-Dev-Subject", ADMIN)
                    .header("Idempotency-Key", "tpl-create-1")
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"name\":\"差旅专用模板\",\"body\":\"依据已审核资料回答：{question}\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().exists("ETag"))
            .andReturn();
    String templateId = JsonPath.read(body(created), "$.data.templateId");
    String etag = created.getResponse().getHeader("ETag");
    assertThat(JsonPath.<Boolean>read(body(created), "$.data.enabled")).isTrue();
    assertThat(JsonPath.<Boolean>read(body(created), "$.data.isGlobal")).isFalse();
    assertThat(JsonPath.<Object>read(body(created), "$.data.updatedAt")).isNotNull();

    // Replaying the same idempotency key returns the same template instead of creating a second.
    String replay =
        body(
            mvc.perform(
                    post("/api/v1/answer-templates")
                        .header("X-Dev-Subject", ADMIN)
                        .header("Idempotency-Key", "tpl-create-1")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"差旅专用模板\",\"body\":\"依据已审核资料回答：{question}\"}"))
                .andExpect(status().isCreated())
                .andReturn());
    assertThat(JsonPath.<String>read(replay, "$.data.templateId")).isEqualTo(templateId);
    assertThat(
            jdbc.sql("SELECT count(*) FROM answer_template WHERE name = '差旅专用模板'")
                .query(Long.class)
                .single())
        .isEqualTo(1);

    // An assistant references the template; the resolved body comes from the entity.
    String spaceId =
        JsonPath.read(
            body(
                mvc.perform(
                        post("/api/v1/spaces")
                            .header("X-Dev-Subject", ADMIN)
                            .with(csrf())
                            .contentType("application/json")
                            .content("{\"name\":\"模板引用测试库\"}"))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.spaceId");
    String assistantId =
        JsonPath.read(
            body(
                mvc.perform(
                        post("/api/v1/assistants")
                            .header("X-Dev-Subject", ADMIN)
                            .header("Idempotency-Key", "tpl-assistant-1")
                            .with(csrf())
                            .contentType("application/json")
                            .content(
                                "{\"name\":\"模板助手\",\"bindings\":[{\"spaceId\":\"%s\",\"role\":\"RETRIEVAL\"}],\"templateId\":\"%s\"}"
                                    .formatted(spaceId, templateId)))
                    .andExpect(status().isCreated())
                    .andReturn()),
            "$.data.assistantId");
    String config =
        body(
            mvc.perform(
                    get("/api/v1/assistants/" + assistantId).header("X-Dev-Subject", ADMIN))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(JsonPath.<String>read(config, "$.data.templateId")).isEqualTo(templateId);
    assertThat(JsonPath.<String>read(config, "$.data.template")).contains("依据已审核资料回答");

    // In-use and global templates cannot be deleted.
    mvc.perform(
            delete("/api/v1/answer-templates/" + templateId)
                .header("X-Dev-Subject", ADMIN)
                .header("If-Match", etag)
                .with(csrf()))
        .andExpect(status().isConflict());

    // Unknown template id in an assistant write is rejected rather than silently ignored.
    mvc.perform(
            post("/api/v1/assistants")
                .header("X-Dev-Subject", ADMIN)
                .header("Idempotency-Key", "tpl-assistant-2")
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"name\":\"无主助手\",\"bindings\":[],\"templateId\":\"%s\"}"
                        .formatted(java.util.UUID.randomUUID())))
        .andExpect(status().isUnprocessableEntity());

    // Update bumps the revision and disabling falls back for consumers.
    String updated =
        body(
            mvc.perform(
                    put("/api/v1/answer-templates/" + templateId)
                        .header("X-Dev-Subject", ADMIN)
                        .header("If-Match", etag)
                        .header("Idempotency-Key", "tpl-update-1")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"差旅专用模板\",\"body\":\"更新后的正文\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(JsonPath.<Number>read(updated, "$.data.revision").longValue()).isEqualTo(1L);
    assertThat(JsonPath.<Boolean>read(updated, "$.data.enabled")).isFalse();
    String fallback =
        body(
            mvc.perform(get("/api/v1/assistants/" + assistantId).header("X-Dev-Subject", ADMIN))
                .andReturn());
    assertThat(JsonPath.<String>read(fallback, "$.data.template")).isNull();

    // Stale If-Match is a precondition failure, not a silent write.
    mvc.perform(
            put("/api/v1/answer-templates/" + templateId)
                .header("X-Dev-Subject", ADMIN)
                .header("If-Match", etag)
                .header("Idempotency-Key", "tpl-update-2")
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\"差旅专用模板\",\"body\":\"再次更新\"}"))
        .andExpect(status().isPreconditionFailed())
        .andExpect(problem("ETAG_MISMATCH"));

    // The global template is protected too.
    String globalId =
        jdbc.sql("SELECT template_id FROM global_setting WHERE singleton_id = 1")
            .query(String.class)
            .optional()
            .orElse(null);
    if (globalId != null) {
        mvc.perform(
                delete("/api/v1/answer-templates/" + globalId)
                    .header("X-Dev-Subject", ADMIN)
                    .header("If-Match", "\"0\"")
                    .with(csrf()))
            .andExpect(status().isConflict());
    }

    // Once detached from the assistant it can be deleted.
    jdbc.sql("UPDATE assistant SET answer_template_id = NULL WHERE id = :id")
        .param("id", java.util.UUID.fromString(assistantId))
        .update();
    String freshEtag =
        mvc.perform(
                get("/api/v1/answer-templates/" + templateId).header("X-Dev-Subject", ADMIN))
            .andReturn()
            .getResponse()
            .getHeader("ETag");
    mvc.perform(
            delete("/api/v1/answer-templates/" + templateId)
                .header("X-Dev-Subject", ADMIN)
                .header("If-Match", freshEtag)
                .with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/answer-templates/" + templateId).header("X-Dev-Subject", ADMIN))
        .andExpect(status().isNotFound());
  }
}
