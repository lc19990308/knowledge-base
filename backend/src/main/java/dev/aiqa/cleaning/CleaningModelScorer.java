package dev.aiqa.cleaning;

import dev.aiqa.settings.ModelConfigRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 用默认模型网关给清洗条目打质量分。
 *
 * <p>开关 {@code aiqa.cleaning.model-score}：
 *
 * <ul>
 *   <li>{@code on}（默认）—— 有可用的默认模型时调用网关；没有配置时整体关闭；
 *   <li>{@code fake} —— 固定分数，用于隔离 E2E，不消耗真实额度；
 *   <li>{@code off} —— 完全不调用。
 * </ul>
 *
 * <p>任何失败（超时、HTTP 错误、无法解析）都返回空，调用方回退规则分，不阻塞审核。
 */
@Component
public class CleaningModelScorer {

  private static final Logger log = LoggerFactory.getLogger(CleaningModelScorer.class);

  private static final int MAX_INPUT_CHARS = 1200;
  private static final int MAX_REASON_CHARS = 120;

  static final String SYSTEM_PROMPT =
      """
      你是知识库数据质量评审。给下面这条待入库文本打分（0-100），考虑：
      1) 是否表达完整、可独立理解；2) 是否有明确事实（对象、金额、条件、期限）；
      3) 是否含噪声（乱码、标签、重复内容）；4) 是否适合作为问答检索的证据。
      只输出一行 JSON，不要解释：{"score":85,"reason":"不超过40字"}
      """;

  /** @param score 0–100 @param reason 不超过 40 字的理由 */
  public record ModelScore(int score, String reason) {}

  private final Environment environment;
  private final io.micrometer.core.instrument.MeterRegistry meters;
  private final ModelConfigRepository configs;
  private final String mode;
  private final Duration timeout;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper mapper = new ObjectMapper();

  public CleaningModelScorer(
      Environment environment,
      io.micrometer.core.instrument.MeterRegistry meters,
      ModelConfigRepository configs,
      @Value("${aiqa.cleaning.model-score:on}") String mode,
      @Value("${aiqa.cleaning.model-timeout:PT20S}") Duration timeout) {
    this.environment = environment;
    this.meters = meters;
    this.configs = configs;
    this.mode = mode == null ? "on" : mode.strip().toLowerCase(java.util.Locale.ROOT);
    this.timeout = timeout;
  }

  /** 本实例是否有能力打分：off 或没有可用的默认模型时返回 false。 */
  public boolean enabled() {
    if ("off".equals(mode)) {
      return false;
    }
    if ("fake".equals(mode)) {
      return true;
    }
    return configs
        .findDefaultConfig()
        .filter(config -> config.enabled())
        .filter(config -> !isBlank(config.baseUrl()))
        .filter(config -> !isBlank(config.modelName()))
        .isPresent();
  }

  /** 给一条正文打分；失败返回空，由调用方回退规则分。 */
  public Optional<ModelScore> score(String title, String body) {
    if ("off".equals(mode)) {
      return Optional.empty();
    }
    if ("fake".equals(mode)) {
      return Optional.of(new ModelScore(82, "本地替身打分（E2E 隔离）"));
    }
    Optional<ModelConfigRepository.ConfigRow> config = configs.findDefaultConfig();
    if (config.isEmpty() || !enabled()) {
      return Optional.empty();
    }
    ModelConfigRepository.ConfigRow model = config.get();
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(endpoint(model)))
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(payload(model, title, body)));
      if ("anthropic".equals(model.protocol())) {
        builder
            .header("x-api-key", credential(model.credentialRef()))
            .header("anthropic-version", "2023-06-01");
      } else {
        builder.header("Authorization", "Bearer " + credential(model.credentialRef()));
      }
      HttpResponse<String> response =
          http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        return counted(Optional.empty(), "http_" + response.statusCode());
      }
      return counted(parse(response.body(), model.protocol()), "ok");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return counted(Optional.empty(), "interrupted");
    } catch (RuntimeException | java.io.IOException ex) {
      log.warn("清洗条目打分失败，回退规则分：{}", ex.toString());
      return counted(Optional.empty(), "failed");
    }
  }

  /** 解析网关返回的一行 JSON；分数越界会被钳制到 0–100。 */
  static Optional<ModelScore> parse(String body, String protocol) {
    ObjectMapper mapper = new ObjectMapper();
    String content;
    try {
      JsonNode root = mapper.readTree(body);
      if ("anthropic".equals(protocol)) {
        content = root.path("content").path(0).path("text").asText("");
      } else {
        content = root.path("choices").path(0).path("message").path("content").asText("");
      }
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
    String json = jsonObject(content);
    if (json == null) {
      return Optional.empty();
    }
    try {
      JsonNode node = mapper.readTree(json);
      JsonNode scoreNode = node.path("score");
      Integer score = null;
      if (scoreNode.isInt() || scoreNode.isLong()) {
        score = scoreNode.asInt();
      } else if (scoreNode.isTextual()) {
        try {
          score = Integer.parseInt(scoreNode.asText("").strip());
        } catch (NumberFormatException ignored) {
          score = null;
        }
      }
      if (score == null) {
        return Optional.empty();
      }
      String reason = node.path("reason").asText("").strip();
      if (reason.length() > MAX_REASON_CHARS) {
        reason = reason.substring(0, MAX_REASON_CHARS);
      }
      return Optional.of(new ModelScore(Math.min(100, Math.max(0, score)), reason));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private Optional<ModelScore> counted(Optional<ModelScore> result, String outcome) {
    meters.counter("aiqa.cleaning.model", "outcome", outcome).increment();
    return result;
  }

  private String endpoint(ModelConfigRepository.ConfigRow model) {
    String base = model.baseUrl().replaceAll("/+$", "");
    return "anthropic".equals(model.protocol())
        ? base + "/v1/messages"
        : base + "/chat/completions";
  }

  private String payload(ModelConfigRepository.ConfigRow model, String title, String body) {
    String user = userPrompt(title, body);
    Map<String, Object> payload;
    if ("anthropic".equals(model.protocol())) {
      payload =
          Map.of(
              "model", model.modelName(),
              "max_tokens", 200,
              "temperature", 0,
              "system", SYSTEM_PROMPT,
              "messages", List.of(Map.of("role", "user", "content", user)));
    } else {
      payload =
          Map.of(
              "model", model.modelName(),
              "max_tokens", 200,
              "temperature", 0,
              "messages",
                  List.of(
                      Map.of("role", "system", "content", SYSTEM_PROMPT),
                      Map.of("role", "user", "content", user)));
    }
    return mapper.writeValueAsString(payload);
  }

  /** 标题 + 截断后的正文，控制单次打分成本。 */
  static String userPrompt(String title, String body) {
    String truncated =
        body.length() > MAX_INPUT_CHARS ? body.substring(0, MAX_INPUT_CHARS) + "…" : body;
    return "标题：" + title + "\n\n正文：\n" + truncated;
  }

  /** 允许模型在 JSON 前后附加解释，只取最外层一对花括号。 */
  private static String jsonObject(String content) {
    if (content == null) {
      return null;
    }
    int start = content.indexOf('{');
    int end = content.lastIndexOf('}');
    return start < 0 || end <= start ? null : content.substring(start, end + 1);
  }

  private String credential(String credentialRef) {
    String suffix =
        credentialRef == null
            ? ""
            : credentialRef.replaceAll("[^A-Za-z0-9]", "_").toLowerCase(java.util.Locale.ROOT);
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
