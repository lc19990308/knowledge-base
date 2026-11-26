package dev.aiqa;

import static dev.aiqa.testsupport.ApiResponseAssertions.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.aiqa.assistant.AssistantRepository;
import dev.aiqa.conversation.ConversationRepository;
import dev.aiqa.conversation.QuestionRepository;
import dev.aiqa.testsupport.TestDatabase;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class ConversationLifecycleIT {

  private static final TestDatabase DATABASE = TestDatabase.fromEnvironment().orElse(null);
  private static final String OWNER = "conversation-owner";
  private static final String OTHER = "conversation-other";
  private static final String CONVERSATIONS = "/api/v1/conversations";

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
    registry.add("aiqa.security.dev-header-auth.enabled", () -> "true");
    registry.add("aiqa.worker.enabled", () -> "false");
    registry.add("aiqa.model.provider", () -> "fake");
  }

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private AssistantRepository assistants;
  @Autowired private QuestionRepository questions;
  @Autowired private ConversationRepository conversations;

  @BeforeAll
  static void requireDatabase() {
    Assumptions.assumeTrue(DATABASE != null, "未配置 IT_DB_* 测试数据库，跳过 PostgreSQL 集成测试");
  }

  @AfterAll
  static void cleanUp() {
    if (DATABASE != null) {
      DATABASE.close();
    }
  }

  @Test
  void ownerCanArchiveRestoreAndSoftDeleteWithoutLosingStoredQuestion() throws Exception {
    String id = create(OWNER, "lifecycle-owner", null);
    String url = CONVERSATIONS + "/" + id;
    String questionId = read(ask(OWNER, url, "lifecycle-first").andExpect(status().isAccepted())
        .andReturn(), "$.data.questionId");
    String questionUrl = "/api/v1/questions/" + questionId;
    call(OWNER, get(url + "/messages"))
        .andExpect(jsonPath("$.data.items.length()").value(2))
        .andExpect(jsonPath("$.data.items[0].messageId").value(questionId + "-q"))
        .andExpect(jsonPath("$.data.items[0].status").doesNotHaveJsonPath())
        .andExpect(jsonPath("$.data.items[1].status").value("QUEUED"))
        .andExpect(jsonPath("$.data.items[1].failureCode").hasJsonPath());
    questions.fail(UUID.fromString(questionId), "MODEL_UNAVAILABLE");
    call(OTHER, put(url + "/archive").content("{\"archived\":true}")).andExpect(status().isNotFound());
    call(OTHER, delete(url)).andExpect(status().isNotFound());
    call(OTHER, get(questionUrl)).andExpect(status().isNotFound());
    call(OTHER, get(url + "/messages")).andExpect(status().isNotFound());
    call(OWNER, put(url + "/archive").content("{\"archived\":\"true\"}"))
        .andExpect(status().isBadRequest());
    call(OWNER, put(url + "/archive").content("{\"archived\":true}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.archived").value(true));
    assertThat(list(OWNER, false)).doesNotContain(id);
    assertThat(list(OWNER, true)).contains(id);
    assertThat(list(OTHER, true)).doesNotContain(id);
    call(OWNER, get(questionUrl)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.failureCode").value("MODEL_UNAVAILABLE"));
    call(OWNER, get(url + "/messages")).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[1].status").value("FAILED"))
        .andExpect(jsonPath("$.data.items[1].failureCode").value("MODEL_UNAVAILABLE"));
    ask(OWNER, url, "lifecycle-second").andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    call(OWNER, put(url + "/archive").content("{\"archived\":false}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.archived").value(false));
    assertThat(list(OWNER, false)).contains(id);
    ask(OWNER, url, "lifecycle-second").andExpect(status().isAccepted());
    call(OWNER, delete(url)).andExpect(status().isNoContent());
    assertThat(list(OWNER, false)).doesNotContain(id);
    assertThat(list(OWNER, true)).doesNotContain(id);
    call(OWNER, get(url + "/messages")).andExpect(status().isNotFound());
    call(OWNER, get(questionUrl)).andExpect(status().isNotFound());
    call(OWNER, put(url + "/archive").content("{\"archived\":false}")).andExpect(status().isNotFound());
    ask(OWNER, url, "lifecycle-first").andExpect(status().isNotFound());
    assertThat(questions.findAny(UUID.fromString(questionId))).isPresent();
  }

  @Test
  void requiresIdempotencyKeyAndReplaysConversation() throws Exception {
    String user = "conversation-create";
    call(user, post(CONVERSATIONS).content("{}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    assertThat(create(user, "lifecycle-create-first", null))
        .isNotEqualTo(create(user, "lifecycle-create-second", null));
    call(user, post(CONVERSATIONS).content("{}").header("Idempotency-Key", "x"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    String key = "lifecycle-create";
    String id = create(user, key, "  A saved title  ");
    assertThat(create(user, key, "A saved title")).isEqualTo(id);
    call(user, post(CONVERSATIONS).content("{\"title\":\"Changed\"}").header("Idempotency-Key", key))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    call(user, delete(CONVERSATIONS + "/" + id)).andExpect(status().isNoContent());
    call(user, post(CONVERSATIONS).content("{\"title\":\"A saved title\"}").header("Idempotency-Key", key))
        .andExpect(status().isNotFound());
  }

  @Test
  void answerJobFailureRollsBackQuestionAndKeyThenRetryCreatesExactlyOne() throws Exception {
    String user = "conversation-rollback";
    String id = create(user, "lifecycle-rollback-conversation", null);
    String url = CONVERSATIONS + "/" + id;
    String key = "lifecycle-rollback";
    jdbc.sql("ALTER TABLE job ADD CONSTRAINT lifecycle_reject_new_answer "
        + "CHECK (job_type <> 'ANSWER_QUESTION') NOT VALID").update();
    try {
      MvcResult failure = ask(user, url, key)
          .andExpect(status().isInternalServerError())
          .andExpect(problem("INTERNAL_ERROR"))
          .andExpect(jsonPath("$.detail").doesNotHaveJsonPath())
          .andReturn();
      assertThat(failure.getResolvedException())
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasRootCauseInstanceOf(SQLException.class)
          .hasStackTraceContaining("lifecycle_reject_new_answer");
      assertThat(jdbc.sql("SELECT count(*) FROM question WHERE conversation_id = :id")
          .param("id", UUID.fromString(id)).query(Long.class).single()).isZero();
      assertThat(jdbc.sql("SELECT count(*) FROM request_idempotency WHERE idempotency_key = :key")
          .param("key", key).query(Long.class).single()).isZero();
    } finally {
      jdbc.sql("ALTER TABLE job DROP CONSTRAINT lifecycle_reject_new_answer").update();
    }
    String acceptedId = read(ask(user, url, key).andExpect(status().isAccepted()).andReturn(), "$.data.questionId");
    assertThat(read(ask(user, url, key).andExpect(status().isAccepted()).andReturn(), "$.data.questionId"))
        .isEqualTo(acceptedId);
    assertThat(jdbc.sql("SELECT count(*) FROM job WHERE question_id = :id AND job_type = 'ANSWER_QUESTION'")
        .param("id", UUID.fromString(acceptedId)).query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT status FROM request_idempotency WHERE idempotency_key = :key")
        .param("key", key).query(String.class).single()).isEqualTo("COMPLETED");
  }

  @Test
  void exposesOnlySelectableModelsAndPersistsSelectionPerQuestion() throws Exception {
    String user = "conversation-model-selection";
    UUID enabled = UUID.randomUUID();
    UUID disabled = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO model_config (id, alias, credential_ref, enabled, base_url, model_name)
        VALUES (:enabled, 'Selectable fixture', 'vault:fixture', true, 'https://example.test/v1', 'one'),
               (:disabled, 'Disabled fixture', 'vault:fixture', false, 'https://example.test/v1', 'two')
        """).param("enabled", enabled).param("disabled", disabled).update();
    call(user, get("/api/v1/model-configs")).andExpect(status().isForbidden());
    call(user, get("/api/v1/model-configs/options")).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].id").value(enabled.toString()))
        .andExpect(jsonPath("$.data.items[0].alias").value("Selectable fixture"))
        .andExpect(jsonPath("$.data.items[0].credentialRef").doesNotHaveJsonPath())
        .andExpect(jsonPath("$.data.items[0].baseUrl").doesNotHaveJsonPath());
    String url = CONVERSATIONS + "/" + create(user, "model-conversation", null);
    String request = "{\"text\":\"What is the policy?\",\"modelId\":\"%s\"}";
    String key = "model-question";
    call(user, post(url + "/questions").header("Idempotency-Key", key)
        .content(request.formatted(disabled))).andExpect(status().isUnprocessableContent());
    String id = read(call(user, post(url + "/questions").header("Idempotency-Key", key)
        .content(request.formatted(enabled))).andExpect(status().isAccepted()).andReturn(), "$.data.questionId");
    assertThat(questions.findAny(UUID.fromString(id)).orElseThrow().requestedModelId()).isEqualTo(enabled);
    ask(user, url, key).andExpect(status().isConflict());
  }

  @Test
  void firstQuestionCreatesConversationWithGeneratedTitle() throws Exception {
    if (assistants.anyEnabled().isEmpty()) {
      assistants.create("First question fixture", null);
    }
    String user = "conversation-first-question";
    String key = "first-question";
    String text = "我想了解差旅报销的具体标准，包括住宿费、交通费和餐补的额度，以及报销需要提交哪些材料？";
    MvcResult accepted =
        call(user, post("/api/v1/questions").header("Idempotency-Key", key)
                .content("{\"text\":\"" + text + "\"}"))
            .andExpect(status().isAccepted())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.data.status").value("QUEUED"))
            .andExpect(jsonPath("$.data.conversation.archived").value(false))
            .andExpect(jsonPath("$.data.conversation.title").isString())
            .andReturn();
    String questionId = read(accepted, "$.data.questionId");
    String conversationId = read(accepted, "$.data.conversation.conversationId");
    String title = read(accepted, "$.data.conversation.title");
    assertThat(title).startsWith("差旅报销的具体标准").endsWith("…");
    assertThat(title.codePointCount(0, title.length())).isLessThanOrEqualTo(30);

    // 同键同内容回放原会话与问题，不重复建会话
    MvcResult replay =
        call(user, post("/api/v1/questions").header("Idempotency-Key", key)
                .content("{\"text\":\"" + text + "\"}"))
            .andExpect(status().isAccepted())
            .andReturn();
    assertThat(read(replay, "$.data.questionId")).isEqualTo(questionId);
    assertThat(read(replay, "$.data.conversation.conversationId")).isEqualTo(conversationId);

    assertThat(list(user, false)).contains(conversationId);
    String storedTitle =
        jdbc.sql("SELECT title FROM conversation WHERE id = :id")
            .param("id", UUID.fromString(conversationId))
            .query(String.class)
            .single();
    assertThat(storedTitle).isEqualTo(title);
    call(user, get("/api/v1/conversations/" + conversationId + "/messages"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].questionId").value(questionId))
        .andExpect(jsonPath("$.data.items[0].text").value(text));
  }

  @Test
  void generatedTitleDoesNotOverwriteExplicitTitle() throws Exception {
    String user = "conversation-explicit-title";
    String id = create(user, "explicit-title", "用户命名标题");
    ask(user, CONVERSATIONS + "/" + id, "explicit-title-question")
        .andExpect(status().isAccepted());
    assertThat(jdbc.sql("SELECT title FROM conversation WHERE id = :id")
        .param("id", UUID.fromString(id)).query(String.class).single())
        .isEqualTo("用户命名标题");
    assertThat(jdbc.sql("SELECT title_auto FROM conversation WHERE id = :id")
        .param("id", UUID.fromString(id)).query(Boolean.class).single())
        .isFalse();
  }

  @Test
  void generatedTitleOnlyUpdatesWhileConversationHasOneQuestion() throws Exception {
    String user = "conversation-title-refine";
    String id = create(user, "title-refine", null);
    UUID conversation = UUID.fromString(id);
    ask(user, CONVERSATIONS + "/" + id, "title-refine-first").andExpect(status().isAccepted());
    assertThat(conversations.setGeneratedTitle(conversation, "差旅报销需提交发票和审批单")).isTrue();
    assertThat(titleOf(id)).isEqualTo("差旅报销需提交发票和审批单");
    ask(user, CONVERSATIONS + "/" + id, "title-refine-second").andExpect(status().isAccepted());
    assertThat(conversations.setGeneratedTitle(conversation, "不该覆盖")).isFalse();
    assertThat(titleOf(id)).isEqualTo("差旅报销需提交发票和审批单");
  }

  private String titleOf(String id) {
    return jdbc.sql("SELECT title FROM conversation WHERE id = :id")
        .param("id", UUID.fromString(id))
        .query(String.class)
        .single();
  }

  @Test
  void emptyConversationReceivesGeneratedTitleOnFirstQuestion() throws Exception {
    String user = "conversation-auto-title";
    String id = create(user, "auto-title", null);
    ask(user, CONVERSATIONS + "/" + id, "auto-title-question").andExpect(status().isAccepted());
    assertThat(jdbc.sql("SELECT title FROM conversation WHERE id = :id")
        .param("id", UUID.fromString(id)).query(String.class).single())
        .isEqualTo("What is the policy");
    assertThat(jdbc.sql("SELECT title_auto FROM conversation WHERE id = :id")
        .param("id", UUID.fromString(id)).query(Boolean.class).single())
        .isTrue();
  }

  private String create(String user, String key, String title) throws Exception {
    if (assistants.anyEnabled().isEmpty()) {
      assistants.create("Lifecycle fixture", null);
    }
    var request = post(CONVERSATIONS).content(title == null ? "{}" : "{\"title\":\"" + title + "\"}");
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    var result = call(user, request).andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.archived").value(false))
        .andExpect(header().exists("Location"));
    if (title != null) {
      result.andExpect(jsonPath("$.data.title").value(title.strip()));
    }
    return read(result.andReturn(), "$.data.conversationId");
  }

  private ResultActions ask(String user, String url, String key) throws Exception {
    return call(user, post(url + "/questions").header("Idempotency-Key", key)
        .content("{\"text\":\"What is the policy?\"}"));
  }

  private List<String> list(String user, boolean archived) throws Exception {
    var request = get(CONVERSATIONS);
    if (archived) {
      request.param("archived", "true");
    }
    return JsonPath.read(call(user, request).andExpect(status().isOk()).andReturn()
        .getResponse().getContentAsString(), "$.data.items[*].conversationId");
  }

  private ResultActions call(String user, MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.header("X-Dev-Subject", user).contentType("application/json").with(csrf()));
  }

  private static String read(MvcResult result, String path) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), path);
  }
}
