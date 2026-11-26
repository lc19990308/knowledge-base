package dev.aiqa.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.aiqa.settings.ModelConfigRepository;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TemplateGenerationModelTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(2);
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void openAiCompatibleReturnsContentAndTokens() throws Exception {
    AtomicReference<String> payload = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] response = """
          {"choices":[{"message":{"content":"【回答要求】\\n先给结论。\\n{question}"}}],
           "usage":{"prompt_tokens":21,"completion_tokens":8}}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();

    TemplateGenerationModel client = new TemplateGenerationModel(
        TIMEOUT, new MockEnvironment().withProperty("aiqa.model.credentials.vault_openai", "test-key"));
    TemplateGenerationModel.Result result = client.generate(config("openai_compatible"), "提示词");

    assertThat(result.text()).contains("先给结论");
    assertThat(result.inputTokens()).isEqualTo(21L);
    assertThat(result.outputTokens()).isEqualTo(8L);
    assertThat(payload.get()).contains("\"stream\":false");
    assertThat(payload.get()).contains("提示词");
  }

  @Test
  void anthropicReadsFirstTextBlock() throws Exception {
    AtomicReference<String> apiKey = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/messages", exchange -> {
      apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
      byte[] response = """
          {"content":[{"type":"text","text":"## 任务\\n基于 {references} 回答。"}],
           "usage":{"input_tokens":12,"output_tokens":5}}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();

    TemplateGenerationModel client = new TemplateGenerationModel(
        TIMEOUT, new MockEnvironment().withProperty("aiqa.model.credentials.vault_anthropic", "key-2"));
    TemplateGenerationModel.Result result = client.generate(config("anthropic"), "提示词");

    assertThat(result.text()).contains("基于 {references}");
    assertThat(result.inputTokens()).isEqualTo(12L);
    assertThat(apiKey.get()).isEqualTo("key-2");
  }

  @Test
  void rateLimitIsRetryable() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      exchange.sendResponseHeaders(429, -1);
      exchange.close();
    });
    server.start();

    TemplateGenerationModel client = new TemplateGenerationModel(
        TIMEOUT, new MockEnvironment().withProperty("aiqa.model.credentials.vault_openai", "test-key"));
    assertThatThrownBy(() -> client.generate(config("openai_compatible"), "提示词"))
        .isInstanceOfSatisfying(TemplateGenerationFailure.class, failure -> {
          assertThat(failure.code()).isEqualTo("MODEL_RATE_LIMITED");
          assertThat(failure.retryable()).isTrue();
        });
  }

  private ModelConfigRepository.ConfigRow config(String protocol) {
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort()
        + (protocol.equals("anthropic") ? "" : "/v1");
    String credentialRef = protocol.equals("anthropic") ? "vault:anthropic" : "vault:openai";
    return new ModelConfigRepository.ConfigRow(
        UUID.randomUUID(), "测试模型", credentialRef, true, 1L,
        protocol, baseUrl, "test-model");
  }
}
