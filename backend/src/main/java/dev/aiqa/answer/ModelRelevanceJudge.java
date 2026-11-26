package dev.aiqa.answer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 用已配置的网关做语义相关性判定。
 *
 * <p>开关 {@code aiqa.answer.relevance}：
 *
 * <ul>
 *   <li>{@code model}（默认）—— 有默认模型时调用网关；没有配置模型时旁路，行为与之前一致；
 *   <li>{@code fake} —— 一律通过，用于隔离 E2E：基准分数不能被判定器"垫"出来；
 *   <li>{@code off} —— 关闭判定。
 * </ul>
 *
 * <p>任何失败（超时、HTTP 错误、响应无法解析）都按失败开放处理，并且只用一行 JSON 决策，
 * 最多读 8 条证据、每条 600 字，避免把整篇文档再发一遍。
 */
@Component
public class ModelRelevanceJudge implements RelevanceJudge {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ModelRelevanceJudge.class);

  private static final int MAX_EVIDENCE = 8;
  private static final int MAX_CHARS = 600;

  static final String SYSTEM_PROMPT =
      """
      你是检索相关性判定器。判断「资料」能否直接支持回答「问题」。
      判定为 false 的情况：资料里完全没有问到对象、金额、条件或期限；只有字面重合但答非所问；
      资料只以否定形式提到该对象（如"不含早餐"）而问题问的是它的具体额度。
      判定为 true 的情况：资料直接给出条款或数值；资料明确说明某项"待核实/待生效/讨论稿"——
      这类仍是有依据的，回答应说明其状态，而不是拒答。
      只输出一行 JSON，不要解释：{"sufficient":true,"reason":"不超过40字"}
      """;

  private final Environment environment;
  private final io.micrometer.core.instrument.MeterRegistry meters;
  private final String mode;
  private final Duration timeout;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper mapper = new ObjectMapper();

  public ModelRelevanceJudge(
      Environment environment,
      io.micrometer.core.instrument.MeterRegistry meters,
      @Value("${aiqa.answer.relevance:model}") String mode,
      @Value("${aiqa.answer.relevance-timeout:PT20S}") Duration timeout) {
    this.environment = environment;
    this.meters = meters;
    this.mode = mode == null ? "model" : mode.strip().toLowerCase(java.util.Locale.ROOT);
    this.timeout = timeout;
  }

  /** 判定候选证据能否支撑这次提问；未配置模型或判定失败时一律放行。 */
  @Override
  public Verdict judge(Model model, String question, List<String> evidenceTexts) {
    if ("off".equals(mode)) {
      return Verdict.pass("相关性判定已关闭");
    }
    if ("fake".equals(mode)) {
      // 隔离 E2E 只验证接线：判定器不能替基准题"判赢"。
      return Verdict.pass("本地替身判定");
    }
    if (model == null || isBlank(model.baseUrl()) || isBlank(model.modelName())) {
      return Verdict.pass("未配置模型，相关性判定旁路");
    }
    if (evidenceTexts.isEmpty()) {
      return new Verdict(false, true, "没有候选证据", null, null);
    }
    String user = userPrompt(question, evidenceTexts);
    try {
      String endpoint = askEndpoint(model);
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
          .timeout(timeout)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(payload(model, user)));
      if ("anthropic".equals(model.protocol())) {
        builder.header("x-api-key", credential(model.credentialRef()))
            .header("anthropic-version", "2023-06-01");
      } else {
        builder.header("Authorization", "Bearer " + credential(model.credentialRef()));
      }
      HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        return counted(Verdict.unavailable("HTTP " + response.statusCode()));
      }
      return counted(parse(response.body(), model.protocol()));
    } catch (RuntimeException | java.io.IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.warn("相关性判定失败，按失败开放继续回答：{}", ex.toString());
      return counted(Verdict.unavailable("判定调用失败"));
    }
  }

  private Verdict counted(Verdict verdict) {
    String outcome =
        !verdict.available() ? "unavailable" : verdict.sufficient() ? "sufficient" : "insufficient";
    meters.counter("aiqa.relevance." + outcome).increment();
    return verdict;
  }

  private String askEndpoint(Model model) {
    String base = model.baseUrl().replaceAll("/+$", "");
    return "anthropic".equals(model.protocol()) ? base + "/v1/messages" : base + "/chat/completions";
  }

  private String payload(Model model, String user) {
    Map<String, Object> body;
    if ("anthropic".equals(model.protocol())) {
      body = Map.of(
          "model", model.modelName(),
          "max_tokens", 200,
          "system", SYSTEM_PROMPT,
          "messages", List.of(Map.of("role", "user", "content", user)));
    } else {
      body = Map.of(
          "model", model.modelName(),
          "temperature", 0,
          "max_tokens", 200,
          "messages", List.of(
              Map.of("role", "system", "content", SYSTEM_PROMPT),
              Map.of("role", "user", "content", user)));
    }
    return mapper.writeValueAsString(body);
  }

  /** 问题 + 编号证据；证据按字符截断，避免判定请求比回答请求还大。 */
  static String userPrompt(String question, List<String> evidenceTexts) {
    StringBuilder prompt = new StringBuilder("问题：").append(question).append("\n\n资料：\n");
    int limit = Math.min(MAX_EVIDENCE, evidenceTexts.size());
    for (int index = 0; index < limit; index++) {
      String text = evidenceTexts.get(index);
      String body = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "…" : text;
      prompt.append('[').append(index + 1).append("] ").append(body).append('\n');
    }
    return prompt.toString();
  }

  Verdict parse(String body, String protocol) {
    String content;
    Long input = null;
    Long output = null;
    try {
      JsonNode root = mapper.readTree(body);
      if ("anthropic".equals(protocol)) {
        content = root.path("content").path(0).path("text").asText("");
        input = optionalLong(root.path("usage").path("input_tokens"));
        output = optionalLong(root.path("usage").path("output_tokens"));
      } else {
        content = root.path("choices").path(0).path("message").path("content").asText("");
        input = optionalLong(root.path("usage").path("prompt_tokens"));
        output = optionalLong(root.path("usage").path("completion_tokens"));
      }
    } catch (RuntimeException ex) {
      return Verdict.unavailable("判定响应无法解析");
    }
    Boolean sufficient = readSufficient(content);
    if (sufficient == null) {
      return Verdict.unavailable("判定响应缺少 sufficient 字段");
    }
    String reason = readReason(content);
    return new Verdict(sufficient, true, reason, input, output);
  }

  private static Boolean readSufficient(String content) {
    String json = jsonObject(content);
    if (json == null) {
      return null;
    }
    try {
      JsonNode node = new ObjectMapper().readTree(json).path("sufficient");
      if (node.isBoolean()) {
        return node.asBoolean();
      }
      if (node.isTextual()) {
        String value = node.asText().strip().toLowerCase(java.util.Locale.ROOT);
        if (List.of("true", "yes", "是").contains(value)) {
          return true;
        }
        if (List.of("false", "no", "否").contains(value)) {
          return false;
        }
      }
      return null;
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static String readReason(String content) {
    String json = jsonObject(content);
    if (json == null) {
      return "";
    }
    try {
      return new ObjectMapper().readTree(json).path("reason").asText("");
    } catch (RuntimeException ex) {
      return "";
    }
  }

  /** 允许模型在 JSON 前后带上解释；只取最外层的一对花括号。 */
  private static String jsonObject(String content) {
    if (content == null) {
      return null;
    }
    int start = content.indexOf('{');
    int end = content.lastIndexOf('}');
    return start < 0 || end <= start ? null : content.substring(start, end + 1);
  }

  private static Long optionalLong(JsonNode node) {
    return node.isMissingNode() || node.isNull() ? null : node.asLong();
  }

  private String credential(String credentialRef) {
    String suffix = credentialRef == null ? "" : credentialRef.replaceAll("[^A-Za-z0-9]", "_")
        .toLowerCase(java.util.Locale.ROOT);
    String value = environment.getProperty("aiqa.model.credentials." + suffix);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("服务端未配置凭据引用 " + credentialRef);
    }
    return value;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
