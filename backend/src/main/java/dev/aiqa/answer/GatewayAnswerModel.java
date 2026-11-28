package dev.aiqa.answer;

import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;

/**
 * 真实模型网关适配器的公共骨架：凭据解析、引用标注、占位符替换、错误归一化。
 * 协议差异（鉴权头、请求体、用量字段名、流式分片格式）由各协议适配器实现。
 */
public abstract class GatewayAnswerModel implements AnswerModel {

  /**
   * 流式读取的兜底看门狗：HttpClient 的请求超时只管到响应头，网关如果只握手不吐字，
   * 读响应体的线程会一直阻塞。到点关闭连接，让读取抛错并归一化成网关不可达。
   */
  private static final ScheduledExecutorService WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "model-stream-watchdog");
            thread.setDaemon(true);
            return thread;
          });

  /**
   * 各协议适配器实现真实调用：网关地址 / 模型名 / 凭据引用由默认模型配置提供。
   *
   * <p>模型每产生一段文本就交给 {@code onDelta}，调用方据此推给前端；
   * 返回值仍是完整回答，引用与用量在流结束后统一计算。
   */
  abstract Rendered ask(
      String baseUrl, String modelName, String credentialRef,
      String question, String template, List<EvidenceSource> evidence,
      Consumer<String> onDelta);

  /** 主流引用标注：证据编号 1 起始，模型在正文中以 [n] 标注。 */
  static final Pattern CITED = Pattern.compile("\\[(\\d{1,2})\\]");

  final HttpClient http;
  final Duration timeout;
  /** 流式响应体的总读取时限；比请求超时宽松，因为长回答本身就要慢慢输出。 */
  final Duration streamTimeout;
  private final Environment environment;

  GatewayAnswerModel(Duration timeout, Duration streamTimeout, Environment environment) {
    this.timeout = timeout;
    this.streamTimeout = streamTimeout;
    this.environment = environment;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  /**
   * 给响应体套一个到点自动关闭的壳，并把字节流解码成按行读取。
   *
   * <p>读不到新数据时 {@code readLine} 会阻塞；到 {@code streamTimeout} 由看门狗关连接，
   * 使读取抛 IOException，调用方据此返回「网关不可达」，不会把 worker 线程永久挂住。
   */
  final BufferedReader streamReader(InputStream body) {
    return new BufferedReader(
        new InputStreamReader(new GuardedBody(body, streamTimeout), StandardCharsets.UTF_8));
  }

  /** 非流式响应（网关忽略 stream 参数时）同样受总读取时限保护。 */
  final InputStream guardedBody(InputStream body) {
    return new GuardedBody(body, streamTimeout);
  }

  /** 带超时关闭的输入流；关闭时取消看门狗，正常读完不留定时任务。 */
  private static final class GuardedBody extends FilterInputStream {

    private final ScheduledFuture<?> guard;

    GuardedBody(InputStream body, Duration timeout) {
      super(body);
      this.guard =
          WATCHDOG.schedule(
              () -> {
                try {
                  close();
                } catch (IOException ignored) {
                  // 连接已经断开，无需再处理。
                }
              },
              timeout.toMillis(),
              TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() throws IOException {
      guard.cancel(false);
      super.close();
    }
  }

  String resolveCredential(String credentialRef) {
    String suffix = credentialRef.replaceAll("[^A-Za-z0-9]", "_").toLowerCase(java.util.Locale.ROOT);
    String propertyName = "aiqa.model.credentials." + suffix;
    String value = environment.getProperty(propertyName);
    if (value == null || value.isBlank()) {
      throw new ApiException(
          ErrorCode.DEPENDENCY_UNAVAILABLE,
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "服务端未配置凭据引用 " + credentialRef);
    }
    return value;
  }

  static String renderTemplate(
      String template, String question, List<EvidenceSource> evidence) {
    StringBuilder references = new StringBuilder();
    StringBuilder validity = new StringBuilder();
    for (int index = 0; index < evidence.size(); index++) {
      EvidenceSource source = evidence.get(index);
      references.append(index + 1)
          .append(". ").append(source.documentName()).append(": ")
          .append(source.text()).append('\n');
      validity.append(index + 1).append(". ").append(source.validity()).append('\n');
    }
    String base = template == null || template.isBlank() ? DEFAULT_TEMPLATE : template;
    boolean hasReferencesPlaceholder = base.contains("{references}");
    boolean hasValidityPlaceholder = base.contains("{validity}");
    String rendered = base
        .replace("{question}", question)
        .replace("{references}", references.toString().stripTrailing())
        .replace("{validity}", validity.toString().stripTrailing());
    if (!hasReferencesPlaceholder) {
      rendered += "\n\n可用证据（必须只依据以下资料，按编号引用）：\n" + references.toString().stripTrailing();
    }
    if (!hasValidityPlaceholder) {
      rendered += "\n\n证据有效性：\n" + validity.toString().stripTrailing();
    }
    return rendered;
  }

  private static final String DEFAULT_TEMPLATE =
      "请回答：{question}\n\n可用证据（按编号引用，标注为 [编号]）：\n{references}\n"
          + "证据有效性标注：\n{validity}";

  static final String CITATION_RULE =
      "\n\n输出要求：引用证据时在该句末尾标注 [编号]，例如 [1]；"
          + "不得引用未列出的资料；证据不支持时明确说明无依据。";

  static List<Integer> parseCitations(String text, int evidenceSize) {
    Map<Integer, Integer> ordered = new java.util.LinkedHashMap<>();
    Matcher matcher = CITED.matcher(text);
    while (matcher.find()) {
      int index = Integer.parseInt(matcher.group(1)) - 1;
      if (index >= 0 && index < evidenceSize) {
        ordered.putIfAbsent(index, index);
      }
    }
    if (ordered.isEmpty()) {
      // 模型没有标注任何引用时不能把全部证据都当成引用：那会让「引用可核对」静默失真，
      // 用户会看到一堆模型并未使用的来源。宁可显式返回空，由前端提示「未标注来源」。
      return List.of();
    }
    return List.copyOf(ordered.keySet());
  }

  ApiException gatewayError(int status) {
    return new ApiException(
        ErrorCode.DEPENDENCY_UNAVAILABLE,
        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
        "模型网关返回 HTTP " + status + "，请检查网关地址与凭据配置");
  }

  ApiException gatewayUnreachable() {
    return new ApiException(
        ErrorCode.DEPENDENCY_UNAVAILABLE,
        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
        "模型网关不可达：请检查 base_url 与网络策略");
  }
}
