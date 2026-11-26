package dev.aiqa.answer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import java.io.BufferedReader;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Anthropic 原生 Messages 协议适配器。
 * 端点 {base_url}/v1/messages；鉴权 x-api-key + anthropic-version；
 * system 为顶层参数；用量 usage.input_tokens/output_tokens；max_tokens 必填。
 *
 * <p>请求带 {@code stream:true}，文本增量来自 {@code content_block_delta} 事件；
 * 网关不支持流式直接返回 JSON 时，按原方式一次性解析，行为与改造前一致。
 */
@Component("anthropicAnswerModel")
public class AnthropicAnswerModel extends GatewayAnswerModel {

  private final ObjectMapper mapper = new ObjectMapper();

  public AnthropicAnswerModel(
      @Value("${aiqa.model.timeout:PT60S}") Duration timeout,
      @Value("${aiqa.model.stream-timeout:PT180S}") Duration streamTimeout,
      Environment environment) {
    super(timeout, streamTimeout, environment);
  }

  @Override
  public Rendered answer(String question, String template, List<EvidenceSource> evidence) {
    throw new UnsupportedOperationException("由 AnswerService 按配置调用 ask");
  }

  @Override
  public Rendered ask(
      String baseUrl, String modelName, String credentialRef,
      String question, String template, List<EvidenceSource> evidence,
      Consumer<String> onDelta) {
    String key = resolveCredential(credentialRef);
    String endpoint = baseUrl.replaceAll("/+$", "") + "/v1/messages";
    String system = renderTemplate(template, question, evidence) + CITATION_RULE;
    String payload;
    try {
      payload = mapper.writeValueAsString(java.util.Map.of(
          "model", modelName,
          "max_tokens", 4096,
          "stream", true,
          "system", system,
          "messages", List.of(
              java.util.Map.of("role", "user", "content", question))));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
    HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
        .timeout(timeout)
        .header("x-api-key", key)
        .header("anthropic-version", "2023-06-01")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();
    HttpResponse<InputStream> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw gatewayUnreachable();
    }
    try (InputStream body = response.body()) {
      if (response.statusCode() / 100 != 2) {
        throw gatewayError(response.statusCode());
      }
      String contentType =
          response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
      if (contentType.contains("json")) {
        String whole = new String(guardedBody(body).readAllBytes(), StandardCharsets.UTF_8);
        Rendered rendered = parse(whole, evidence.size());
        if (!rendered.text().isEmpty()) {
          onDelta.accept(rendered.text());
        }
        return rendered;
      }
      return parseStream(body, evidence.size(), onDelta);
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw gatewayUnreachable();
    }
  }

  /**
   * 逐行读取 SSE：文本在 {@code content_block_delta} 里，入参 Tokens 在 {@code message_start}，
   * 出参 Tokens 在 {@code message_delta}；{@code message_stop} 结束。
   *
   * <p>事件类型写在 data 的 type 字段里，所以这里只解析 data 行，不依赖 event 行。
   */
  private Rendered parseStream(InputStream body, int evidenceSize, Consumer<String> onDelta)
      throws java.io.IOException {
    StringBuilder text = new StringBuilder();
    Long[] usage = new Long[2];
    try (BufferedReader reader = streamReader(body)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.startsWith("data:")) {
          continue;
        }
        String data = line.substring(5).strip();
        if (data.isEmpty()) {
          continue;
        }
        JsonNode event;
        try {
          event = mapper.readTree(data);
        } catch (Exception malformed) {
          continue;
        }
        String type = event.path("type").asText("");
        switch (type) {
          case "message_start" -> readUsage(event.path("message").path("usage"), usage, true);
          case "message_delta" -> readUsage(event.path("usage"), usage, false);
          case "content_block_delta" -> {
            JsonNode delta = event.path("delta");
            if ("text_delta".equals(delta.path("type").asText(""))) {
              String piece = delta.path("text").asText("");
              if (!piece.isEmpty()) {
                text.append(piece);
                onDelta.accept(piece);
              }
            }
          }
          case "message_stop" -> {
            return rendered(text.toString(), evidenceSize, usage);
          }
          case "error" -> throw new ApiException(
              ErrorCode.DEPENDENCY_UNAVAILABLE,
              org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
              "模型网关返回错误：" + event.path("error").path("message").asText("未知错误"));
          default -> {
            // ping 等事件与正文无关。
          }
        }
      }
    }
    return rendered(text.toString(), evidenceSize, usage);
  }

  /** message_start 带 input_tokens，message_delta 带 output_tokens；缺哪个就保留 null。 */
  private static void readUsage(JsonNode usage, Long[] target, boolean inputTokens) {
    if (usage == null || !usage.isObject()) {
      return;
    }
    String field = inputTokens ? "input_tokens" : "output_tokens";
    if (usage.path(field).isNumber()) {
      target[inputTokens ? 0 : 1] = usage.path(field).asLong();
    }
  }

  private Rendered rendered(String text, int evidenceSize, Long[] usage) {
    return new Rendered(text, parseCitations(text, evidenceSize), usage[0], usage[1]);
  }

  /** 非流式响应解析，与改造前保持一致，供忽略 stream 的网关使用。 */
  private Rendered parse(String body, int evidenceSize) {
    try {
      JsonNode root = mapper.readTree(body);
      StringBuilder text = new StringBuilder();
      for (JsonNode block : root.path("content")) {
        if ("text".equals(block.path("type").asText())) {
          text.append(block.path("text").asText());
        }
      }
      JsonNode usage = root.path("usage");
      Long input = usage.path("input_tokens").isMissingNode()
          ? null : usage.path("input_tokens").asLong();
      Long output = usage.path("output_tokens").isMissingNode()
          ? null : usage.path("output_tokens").asLong();
      return new Rendered(
          text.toString(), parseCitations(text.toString(), evidenceSize), input, output);
    } catch (Exception ex) {
      throw new ApiException(
          ErrorCode.DEPENDENCY_UNAVAILABLE,
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "模型网关响应无法解析");
    }
  }
}
