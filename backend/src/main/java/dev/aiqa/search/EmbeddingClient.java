package dev.aiqa.search;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 文本块与提问的嵌入向量。
 *
 * <p>默认关闭；配置 {@code aiqa.embedding.enabled=true} 并指向一个 OpenAI 兼容的
 * {@code /embeddings} 端点（本机 llama.cpp 即可）后启用。嵌入失败不抛给调用方：
 * 索引侧存 NULL，检索侧回退词法，避免嵌入服务抖动导致导入或问答整体失败。
 */
@Component
public class EmbeddingClient {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EmbeddingClient.class);

  /** 单次请求最多多少段文本；太小浪费往返，太大超过本地服务的批大小。 */
  private static final int BATCH = 16;

  private final boolean enabled;
  private final String endpoint;
  private final String model;
  private final Duration timeout;
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public EmbeddingClient(
      @Value("${aiqa.embedding.enabled:false}") boolean enabled,
      @Value("${aiqa.embedding.url:http://127.0.0.1:3437/v1}") String url,
      @Value("${aiqa.embedding.model:Qwen3-Embedding-0.6B}") String model,
      @Value("${aiqa.embedding.timeout:PT30S}") Duration timeout) {
    this.enabled = enabled;
    this.endpoint = url.replaceAll("/+$", "") + "/embeddings";
    this.model = model;
    this.timeout = timeout;
  }

  public boolean enabled() {
    return enabled;
  }

  /** 单段文本；失败或未启用时返回 null。 */
  public float[] embed(String text) {
    if (!enabled || text == null || text.isBlank()) {
      return null;
    }
    List<float[]> vectors = embedAll(List.of(text));
    return vectors.isEmpty() ? null : vectors.get(0);
  }

  /**
   * 批量嵌入，返回顺序与入参一致；任一批失败时该批位置为 null。
   * 全部失败时返回空列表，调用方据此决定是否回退。
   */
  public List<float[]> embedAll(List<String> texts) {
    if (!enabled || texts.isEmpty()) {
      return List.of();
    }
    List<float[]> vectors = new ArrayList<>(texts.size());
    for (int start = 0; start < texts.size(); start += BATCH) {
      List<String> batch = texts.subList(start, Math.min(start + BATCH, texts.size()));
      vectors.addAll(embedBatch(batch));
    }
    return vectors;
  }

  /** 发送一批文本；失败时返回与入参等长的 null 列表，调用方据此回退。 */
  private List<float[]> embedBatch(List<String> batch) {
    List<float[]> empty = new ArrayList<>();
    for (int i = 0; i < batch.size(); i++) {
      empty.add(null);
    }
    try {
      String payload = mapper.writeValueAsString(
          java.util.Map.of("model", model, "input", batch));
      HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
          .timeout(timeout)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(payload))
          .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        log.warn("嵌入服务返回 HTTP {}：{}", response.statusCode(), response.body());
        return empty;
      }
      List<float[]> parsed = parse(response.body());
      if (parsed.size() != batch.size()) {
        log.warn("嵌入返回条数不一致：期望 {}，实际 {}", batch.size(), parsed.size());
        return empty;
      }
      return parsed;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return empty;
    } catch (RuntimeException | java.io.IOException ex) {
      log.warn("嵌入调用失败，回退词法检索：{}", ex.toString());
      return empty;
    }
  }

  /** 解析 OpenAI 兼容响应：data[i].embedding 是浮点数组。 */
  List<float[]> parse(String body) {
    JsonNode root = mapper.readTree(body);
    JsonNode data = root.path("data");
    if (!data.isArray()) {
      return List.of();
    }
    List<float[]> vectors = new ArrayList<>(data.size());
    for (JsonNode item : data) {
      JsonNode values = item.path("embedding");
      if (!values.isArray()) {
        return List.of();
      }
      float[] vector = new float[values.size()];
      for (int index = 0; index < values.size(); index++) {
        vector[index] = (float) values.get(index).asDouble();
      }
      vectors.add(vector);
    }
    return vectors;
  }

  /** 余弦相似度；维度不一致或零向量时返回 0。 */
  public static double cosine(float[] left, float[] right) {
    if (left == null || right == null || left.length != right.length) {
      return 0;
    }
    double dot = 0;
    double leftNorm = 0;
    double rightNorm = 0;
    for (int index = 0; index < left.length; index++) {
      dot += left[index] * right[index];
      leftNorm += left[index] * left[index];
      rightNorm += right[index] * right[index];
    }
    if (leftNorm == 0 || rightNorm == 0) {
      return 0;
    }
    return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
  }

  /** PostgreSQL real[] 字面量；null 保持 null 以便列存 NULL。 */
  public static String literal(float[] vector) {
    if (vector == null || vector.length == 0) {
      return null;
    }
    StringBuilder literal = new StringBuilder("{");
    for (int index = 0; index < vector.length; index++) {
      if (index > 0) {
        literal.append(',');
      }
      literal.append(String.format(java.util.Locale.ROOT, "%.6f", vector[index]));
    }
    return literal.append('}').toString();
  }
}
