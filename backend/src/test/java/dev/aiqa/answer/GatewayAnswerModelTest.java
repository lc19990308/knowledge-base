package dev.aiqa.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class GatewayAnswerModelTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"deepseek-v4.1-flash", "glm-5.3-flash"})
  void openAiCompatibleRequestUsesBearerMessagesAndOpenAiUsageFields(String modelId) throws Exception {
    String basePath = "/inference/openai/v1";
    AtomicReference<String> auth = new AtomicReference<>();
    AtomicReference<String> payload = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(basePath + "/chat/completions", exchange -> {
      auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] response = "{\"choices\":[{\"message\":{\"content\":\"依据 [1]\"}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":4}}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    MockEnvironment environment = new MockEnvironment()
        .withProperty("aiqa.model.credentials.vault_openai", "test-key");
    OpenAiCompatibleAnswerModel adapter =
        new OpenAiCompatibleAnswerModel(TIMEOUT, TIMEOUT, environment);
    List<String> deltas = new ArrayList<>();

    AnswerModel.Rendered rendered = adapter.ask(
        "http://127.0.0.1:" + server.getAddress().getPort() + basePath,
        modelId, "vault:openai", "问题", "只依据资料回答。",
        List.of(new AnswerModel.EvidenceSource("s", "p", 1, "d", "policy.txt", "正文证据", "EFFECTIVE")),
        deltas::add);

    assertThat(auth.get()).isEqualTo("Bearer test-key");
    assertThat(payload.get()).contains(modelId, "问题", "policy.txt", "正文证据", "[1]", "\"stream\":true");
    assertThat(rendered.text()).isEqualTo("依据 [1]");
    assertThat(rendered.citedEvidenceIndexes()).containsExactly(0);
    assertThat(rendered.inputTokens()).isEqualTo(11);
    assertThat(rendered.outputTokens()).isEqualTo(4);
    // 网关忽略 stream 直接返回 JSON 时，整段作为一次增量下发，前端仍能逐字渲染。
    assertThat(deltas).containsExactly("依据 [1]");
  }

  @Test
  void anthropicRequestUsesNativeHeadersTopLevelSystemAndAnthropicUsageFields() throws Exception {
    AtomicReference<String> apiKey = new AtomicReference<>();
    AtomicReference<String> version = new AtomicReference<>();
    AtomicReference<String> payload = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/messages", exchange -> {
      apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
      version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
      payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] response = "{\"content\":[{\"type\":\"text\",\"text\":\"回答 [1]\"}],\"usage\":{\"input_tokens\":21,\"output_tokens\":8}}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    MockEnvironment environment = new MockEnvironment()
        .withProperty("aiqa.model.credentials.vault_anthropic", "anthropic-key");
    AnthropicAnswerModel adapter = new AnthropicAnswerModel(TIMEOUT, TIMEOUT, environment);
    List<String> deltas = new ArrayList<>();

    AnswerModel.Rendered rendered = adapter.ask(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "claude-test", "vault:anthropic", "问题", "模板",
        List.of(new AnswerModel.EvidenceSource("s", "p", 1, "d", "policy.txt", "正文证据", "EFFECTIVE")),
        deltas::add);

    assertThat(apiKey.get()).isEqualTo("anthropic-key");
    assertThat(version.get()).isEqualTo("2023-06-01");
    assertThat(payload.get()).contains("claude-test", "max_tokens", "system", "正文证据", "\"stream\":true");
    assertThat(rendered.text()).isEqualTo("回答 [1]");
    assertThat(rendered.citedEvidenceIndexes()).containsExactly(0);
    assertThat(rendered.inputTokens()).isEqualTo(21);
    assertThat(rendered.outputTokens()).isEqualTo(8);
    assertThat(deltas).containsExactly("回答 [1]");
  }

  @Test
  void openAiCompatibleStreamsEachDeltaBeforeCompletion() throws Exception {
    List<String> deltas = new ArrayList<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      writeSse(exchange.getResponseBody(), List.of(
          "data: {\"choices\":[{\"delta\":{\"content\":\"依据 \"}}]}\n\n",
          "data: {\"choices\":[{\"delta\":{\"content\":\"[1]\"}}]}\n\n",
          "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3}}\n\n",
          "data: [DONE]\n\n"));
      exchange.close();
    });
    server.start();
    MockEnvironment environment = new MockEnvironment()
        .withProperty("aiqa.model.credentials.vault_openai", "test-key");
    OpenAiCompatibleAnswerModel adapter =
        new OpenAiCompatibleAnswerModel(TIMEOUT, TIMEOUT, environment);

    AnswerModel.Rendered rendered = adapter.ask(
        baseUrl("/v1"), "stream-model", "vault:openai", "问题", "模板",
        List.of(new AnswerModel.EvidenceSource("s", "p", 1, "d", "policy.txt", "正文证据", "EFFECTIVE")),
        deltas::add);

    assertThat(deltas).containsExactly("依据 ", "[1]");
    assertThat(rendered.text()).isEqualTo("依据 [1]");
    assertThat(rendered.citedEvidenceIndexes()).containsExactly(0);
    assertThat(rendered.inputTokens()).isEqualTo(9);
    assertThat(rendered.outputTokens()).isEqualTo(3);
  }

  @Test
  void anthropicStreamsTextDeltasAndUsage() throws Exception {
    List<String> deltas = new ArrayList<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/messages", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      writeSse(exchange.getResponseBody(), List.of(
          "event: message_start\n",
          "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":12,\"output_tokens\":1}}}\n\n",
          "event: content_block_delta\n",
          "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"回答 \"}}\n\n",
          "event: content_block_delta\n",
          "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"[1]\"}}\n\n",
          "event: message_delta\n",
          "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}\n\n",
          "event: message_stop\n",
          "data: {\"type\":\"message_stop\"}\n\n"));
      exchange.close();
    });
    server.start();
    MockEnvironment environment = new MockEnvironment()
        .withProperty("aiqa.model.credentials.vault_anthropic", "anthropic-key");
    AnthropicAnswerModel adapter = new AnthropicAnswerModel(TIMEOUT, TIMEOUT, environment);

    AnswerModel.Rendered rendered = adapter.ask(
        baseUrl(""), "claude-test", "vault:anthropic", "问题", "模板",
        List.of(new AnswerModel.EvidenceSource("s", "p", 1, "d", "policy.txt", "正文证据", "EFFECTIVE")),
        deltas::add);

    assertThat(deltas).containsExactly("回答 ", "[1]");
    assertThat(rendered.text()).isEqualTo("回答 [1]");
    assertThat(rendered.citedEvidenceIndexes()).containsExactly(0);
    assertThat(rendered.inputTokens()).isEqualTo(12);
    assertThat(rendered.outputTokens()).isEqualTo(5);
  }

  @Test
  void gatewayHttpErrorIsReportedAsDependencyUnavailable() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(429, -1);
      exchange.close();
    });
    server.start();
    MockEnvironment environment = new MockEnvironment()
        .withProperty("aiqa.model.credentials.vault_openai", "test-key");
    OpenAiCompatibleAnswerModel adapter =
        new OpenAiCompatibleAnswerModel(TIMEOUT, TIMEOUT, environment);

    Consumer<String> ignore = delta -> {};
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.ask(
            baseUrl("/v1"), "stream-model", "vault:openai", "问题", "模板",
            List.of(new AnswerModel.EvidenceSource("s", "p", 1, "d", "policy.txt", "正文证据", "EFFECTIVE")),
            ignore))
        .isInstanceOf(dev.aiqa.common.ApiException.class)
        .hasMessageContaining("模型网关返回 HTTP 429");
  }

  @Test
  void answerWithoutCitationMarkersDoesNotBackfillEveryEvidence() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      exchange.getRequestBody().readAllBytes();
      byte[] response = "{\"choices\":[{\"message\":{\"content\":\"根据资料，返程后十五个工作日内提交。\"}}]}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    MockEnvironment environment = new MockEnvironment()
        .withProperty("aiqa.model.credentials.vault_openai", "test-key");
    OpenAiCompatibleAnswerModel adapter =
        new OpenAiCompatibleAnswerModel(TIMEOUT, TIMEOUT, environment);

    AnswerModel.Rendered rendered = adapter.ask(
        baseUrl("/v1"), "no-cite-model", "vault:openai", "报销时限？", "模板",
        List.of(
            new AnswerModel.EvidenceSource("s", "p", 1, "d", "policy.txt", "证据一", "EFFECTIVE"),
            new AnswerModel.EvidenceSource("s", "p", 1, "e", "policy2.txt", "证据二", "EFFECTIVE")),
        delta -> {});

    // 模型没写 [n] 时引用必须为空：回填全部证据会让「引用可核对」静默失真。
    assertThat(rendered.citedEvidenceIndexes()).isEmpty();
  }

  private String baseUrl(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  /** 按行写出 SSE 分片并立即刷出，模拟网关边生成边推送。 */
  private static void writeSse(java.io.OutputStream out, List<String> chunks)
      throws java.io.IOException {
    for (String chunk : chunks) {
      out.write(chunk.getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
    out.close();
  }
}
