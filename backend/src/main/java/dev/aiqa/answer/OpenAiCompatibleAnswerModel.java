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
 * OpenAI 兼容协议适配器：覆盖 OpenAI / DeepSeek / Kimi / 智谱 / 通义 / 豆包 / 百度 v2 /
 * 腾讯混元，以及 Gemini 官方兼容层与 OpenRouter 等聚合网关。
 * 端点 {base_url}/chat/completions；鉴权 Bearer；用量 usage.prompt_tokens/completion_tokens。
 *
 * <p>请求带 {@code stream:true}，增量来自 {@code choices[0].delta.content}；
 * 网关若不支持流式而直接返回 JSON，则一次性解析并把整段作为一段增量交给调用方，行为与之前一致。
 */
@Component("openAiCompatibleAnswerModel")
public class OpenAiCompatibleAnswerModel extends GatewayAnswerModel {

  private final ObjectMapper mapper = new ObjectMapper();

  public OpenAiCompatibleAnswerModel(
      @Value("${aiqa.model.timeout:PT60S}") Duration timeout,
      @Value("${aiqa.model.stream-timeout:PT180S}") Duration streamTimeout,
      Environment environment) {
    super(timeout, streamTimeout, environment);
  }

  @Override
  public Rendered answer(String question, String template, List<EvidenceSource> evidence) {
    throw new UnsupportedOperationException("由 AnswerService 按配置调用 ask");
  }

  /** AnswerService 传入解析后的网关配置执行真实调用，并在生成过程中回调增量。 */
  @Override
  public Rendered ask(
      String baseUrl, String modelName, String credentialRef,
      String question, String template, List<EvidenceSource> evidence,
      Consumer<String> onDelta) {
    String key = resolveCredential(credentialRef);
    String endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";
    String system = renderTemplate(template, question, evidence) + CITATION_RULE;
    String payload;
    try {
      payload = mapper.writeValueAsString(java.util.Map.of(
          "model", modelName,
          "temperature", 0.2,
          "stream", true,
          "stream_options", java.util.Map.of("include_usage", true),
          "messages", List.of(
              java.util.Map.of("role", "system", "content", system),
              java.util.Map.of("role", "user", "content", question))));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
    HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
        .timeout(timeout)
        .header("Authorization", "Bearer " + key)
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
        // 网关忽略了 stream：一次性取完整回答，仍按一段增量下发，前端表现一致。
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
   * 逐行读取 SSE：每行形如 {@code data: {...}}，{@code data: [DONE]} 结束。
   *
   * <p>用量通常在最后一条只有 usage、没有 choices 的分片里，读到就记下；
   * 非 JSON 的行（心跳、空行）直接跳过，不让个别异常分片打断整段回答。
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
        if ("[DONE]".equals(data)) {
          break;
        }
        JsonNode chunk;
        try {
          chunk = mapper.readTree(data);
        } catch (Exception malformed) {
          continue;
        }
        readUsage(chunk.path("usage"), usage);
        for (JsonNode choice : chunk.path("choices")) {
          JsonNode delta = choice.path("delta").path("content");
          if (delta.isTextual()) {
            String piece = delta.asText();
            if (!piece.isEmpty()) {
              text.append(piece);
              onDelta.accept(piece);
            }
          }
        }
      }
    }
    String whole = text.toString();
    return new Rendered(
        whole, parseCitations(whole, evidenceSize), usage[0], usage[1]);
  }

  /** 读取 usage 分片；缺失的字段保持 null，和真实网关未上报用量时一致。 */
  private static void readUsage(JsonNode usage, Long[] target) {
    if (usage == null || !usage.isObject()) {
      return;
    }
    if (usage.path("prompt_tokens").isNumber()) {
      target[0] = usage.path("prompt_tokens").asLong();
    }
    if (usage.path("completion_tokens").isNumber()) {
      target[1] = usage.path("completion_tokens").asLong();
    }
  }

  /** 非流式响应解析，与改造前保持一致，供忽略 stream 的网关使用。 */
  private Rendered parse(String body, int evidenceSize) {
    try {
      JsonNode root = mapper.readTree(body);
      String text = root.path("choices").path(0).path("message").path("content").asText("");
      JsonNode usage = root.path("usage");
      Long input = usage.path("prompt_tokens").isMissingNode()
          ? null : usage.path("prompt_tokens").asLong();
      Long output = usage.path("completion_tokens").isMissingNode()
          ? null : usage.path("completion_tokens").asLong();
      return new Rendered(
          text, parseCitations(text, evidenceSize), input, output);
    } catch (Exception ex) {
      throw new ApiException(
          ErrorCode.DEPENDENCY_UNAVAILABLE,
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "模型网关响应无法解析");
    }
  }
}
