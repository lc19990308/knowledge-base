package dev.aiqa;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.aiqa.testsupport.TestDatabase;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 会话有效期（真实 HTTP，含容器级 Spring Session 过滤器）：
 * 登录建立会话并携带轮换后的 QA_SESSION Cookie；超过绝对期限后同一 Cookie 立即 401。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionExpiryIT {

  private static final TestDatabase DATABASE = TestDatabase.fromEnvironment().orElse(null);

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
    registry.add("spring.flyway.create-schemas", () -> "true");
    registry.add("aiqa.worker.enabled", () -> "false");
    registry.add("aiqa.security.dev-header-auth.enabled", () -> "false");
    // 引导账号（admin/user）在本上下文自动创建，供真实登录使用。
    registry.add("aiqa.session.absolute-timeout", () -> "PT1S");
    // 登录必填验证码；测试固定为 K7QM，先取 captchaId 再回传字符。
    registry.add("aiqa.login.captcha.fixed-code", () -> "K7QM");
  }

  @LocalServerPort private int port;

  @AfterAll
  void cleanUp() {
    if (DATABASE != null) {
      DATABASE.close();
    }
  }

  private HttpClient browser() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager())
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  private URI app(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private HttpResponse<String> send(
      HttpClient client, String method, String path, String csrf, String body) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(app(path)).timeout(Duration.ofSeconds(10));
    if (csrf != null) {
      builder.header("X-CSRF-TOKEN", csrf);
    }
    if (body != null) {
      builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    } else {
      builder.GET();
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void absoluteTimeoutKillsTheSession() throws Exception {
    Assumptions.assumeTrue(DATABASE != null, "未配置 IT_DB_* 测试数据库，跳过 PostgreSQL 集成测试");
    HttpClient client = browser();

    // CSRF 令牌 + 会话 Cookie（公开接口）。
    HttpResponse<String> csrfResponse = send(client, "GET", "/api/v1/auth/csrf", null, null);
    assertThat(csrfResponse.statusCode()).isEqualTo(200);
    String csrf = JsonPath.read(csrfResponse.body(), "$.data.token");

    // 先取一枚验证码，再带 captchaId 与固定字符登录。
    HttpResponse<String> challenge = send(client, "GET", "/api/v1/auth/captcha", null, null);
    assertThat(challenge.statusCode()).isEqualTo(200);
    String captchaId = JsonPath.read(challenge.body(), "$.data.captchaId");

    // 引导管理员真实登录 → 会话 Cookie 生效。
    HttpResponse<String> login =
        send(
            client,
            "POST",
            "/api/v1/auth/login",
            csrf,
            "{\"username\":\"admin\",\"password\":\"admin123\",\"captchaId\":\""
                + captchaId
                + "\",\"captchaCode\":\"K7QM\"}");
    assertThat(login.statusCode()).isEqualTo(200);
    assertThat(client.cookieHandler().get()).isNotNull();

    HttpResponse<String> me = send(client, "GET", "/api/v1/auth/me", null, null);
    assertThat(me.statusCode()).isEqualTo(200);

    // 超过绝对期限（PT1S）：同一 Cookie 立即 401。
    Thread.sleep(1200);
    HttpResponse<String> afterExpiry = send(client, "GET", "/api/v1/auth/me", null, null);
    assertThat(afterExpiry.statusCode()).isEqualTo(401);
  }
}
