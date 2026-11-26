package dev.aiqa.template;

import dev.aiqa.settings.ModelConfigRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 模板生成的模型调用：复用模型配置的协议、地址、模型名与凭据约定，但使用独立的非流式请求。
 * 与问答适配器分开，避免把「引用规则 / 证据占位符」注入到模板编写场景。
 */
@Component
public class TemplateGenerationModel {

  private static final String ANTHROPIC_VERSION = "2023-06-01";
  private static final int ANTHROPIC_MAX_TOKENS = 4000;

  public record Result(String text, Long inputTokens, Long outputTokens) {}

  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http;
  private final Duration timeout;
  private final Environment environment;

  public TemplateGenerationModel(
      @Value("${aiqa.model.timeout:PT60S}") Duration timeout, Environment environment) {
    this.timeout = timeout;
    this.environment = environment;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public Result generate(ModelConfigRepository.ConfigRow config, String prompt) {
    String key = resolveCredential(config.credentialRef());
    return switch (config.protocol()) {
      case "openai_compatible" -> openAiCompatible(config, key, prompt);
      case "anthropic" -> anthropic(config, key, prompt);
      default -> throw new TemplateGenerationFailure(
          "MODEL_UNAVAILABLE", false, "不支持的模型协议：" + config.protocol());
    };
  }

  private Result openAiCompatible(ModelConfigRepository.ConfigRow config, String key, String prompt) {
    String endpoint = config.baseUrl().replaceAll("/+$", "") + "/chat/completions";
    String payload;
    try {
      payload = mapper.writeValueAsString(java.util.Map.of(
          "model", config.modelName(),
          "temperature", 0.3,
          "stream", false,
          "messages", List.of(
              java.util.Map.of("role", "system", "content", prompt),
              java.util.Map.of("role", "user", "content", "请生成符合要求的回答模板正文。"))));
    } catch (RuntimeException ex) {
      throw new TemplateGenerationFailure("INTERNAL_ERROR", false, "生成请求序列化失败");
    }
    HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
        .timeout(timeout)
        .header("Authorization", "Bearer " + key)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();
    JsonNode root = send(request);
    JsonNode message = root.path("choices").path(0).path("message").path("content");
    String text = message.isTextual() ? message.asText() : "";
    JsonNode usage = root.path("usage");
    return new Result(text, longOrNull(usage.get("prompt_tokens")), longOrNull(usage.get("completion_tokens")));
  }

  private Result anthropic(ModelConfigRepository.ConfigRow config, String key, String prompt) {
    String endpoint = config.baseUrl().replaceAll("/+$", "") + "/v1/messages";
    String payload;
    try {
      payload = mapper.writeValueAsString(java.util.Map.of(
          "model", config.modelName(),
          "max_tokens", ANTHROPIC_MAX_TOKENS,
          "temperature", 0.3,
          "system", prompt,
          "messages", List.of(
              java.util.Map.of("role", "user", "content", "请生成符合要求的回答模板正文。"))));
    } catch (RuntimeException ex) {
      throw new TemplateGenerationFailure("INTERNAL_ERROR", false, "生成请求序列化失败");
    }
    HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
        .timeout(timeout)
        .header("x-api-key", key)
        .header("anthropic-version", ANTHROPIC_VERSION)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();
    JsonNode root = send(request);
    StringBuilder text = new StringBuilder();
    for (JsonNode block : root.path("content")) {
      if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
        text.append(block.path("text").asText());
      }
    }
    JsonNode usage = root.path("usage");
    return new Result(text.toString(), longOrNull(usage.get("input_tokens")), longOrNull(usage.get("output_tokens")));
  }

  /** 统一发送与错误归一化：超时/网关不可用归类为可重试，鉴权与参数错误直接失败。 */
  private JsonNode send(HttpRequest request) {
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (HttpTimeoutException ex) {
      throw new TemplateGenerationFailure("MODEL_TIMEOUT", true, "模型调用超时");
    } catch (IOException ex) {
      throw new TemplateGenerationFailure("MODEL_UNAVAILABLE", true, "模型网关不可达");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new TemplateGenerationFailure("MODEL_UNAVAILABLE", true, "模型调用被中断");
    }
    int status = response.statusCode();
    if (status == 429) {
      throw new TemplateGenerationFailure("MODEL_RATE_LIMITED", true, "模型网关限流");
    }
    if (status < 200 || status >= 300) {
      throw new TemplateGenerationFailure(
          "MODEL_UNAVAILABLE", true, "模型网关返回 " + status);
    }
    try {
      return mapper.readTree(response.body());
    } catch (RuntimeException ex) {
      throw new TemplateGenerationFailure("INVALID_MODEL_OUTPUT", false, "模型响应不是合法 JSON");
    }
  }

  /** 凭据引用 → 服务端环境变量，与问答适配器的命名规则一致。 */
  private String resolveCredential(String credentialRef) {
    String suffix =
        credentialRef.replaceAll("[^A-Za-z0-9]", "_").toLowerCase(java.util.Locale.ROOT);
    String value = environment.getProperty("aiqa.model.credentials." + suffix);
    if (value == null || value.isBlank()) {
      throw new TemplateGenerationFailure(
          "MODEL_UNAVAILABLE", false, "服务端未配置凭据引用 " + credentialRef);
    }
    return value;
  }

  /** 未上报保持 NULL，不记 0。 */
  private static Long longOrNull(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.asLong();
  }
}
