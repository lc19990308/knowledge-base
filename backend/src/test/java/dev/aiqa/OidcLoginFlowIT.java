package dev.aiqa;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.aiqa.testsupport.FakeOidcProvider;
import dev.aiqa.testsupport.TestDatabase;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the real authorization-code flow against an in-process OIDC provider: redirect to the
 * provider, callback with a code, server-side token exchange and signing-key validation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OidcLoginFlowIT {

  private static final TestDatabase DATABASE = TestDatabase.fromEnvironment().orElse(null);
  private static final FakeOidcProvider PROVIDER = startProvider();

  private static FakeOidcProvider startProvider() {
    if (DATABASE == null) {
      return null;
    }
    try {
      return FakeOidcProvider.start("test-client", "test-secret");
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

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
    registry.add("spring.security.oauth2.client.registration.enterprise.provider", () -> "enterprise");
    registry.add("spring.security.oauth2.client.registration.enterprise.client-id", () -> "test-client");
    registry.add("spring.security.oauth2.client.registration.enterprise.client-secret", () -> "test-secret");
    registry.add(
        "spring.security.oauth2.client.registration.enterprise.authorization-grant-type",
        () -> "authorization_code");
    registry.add(
        "spring.security.oauth2.client.registration.enterprise.redirect-uri",
        () -> "{baseUrl}/login/oauth2/code/enterprise");
    registry.add("spring.security.oauth2.client.registration.enterprise.scope", () -> "openid,profile");
    registry.add(
        "spring.security.oauth2.client.provider.enterprise.issuer-uri", () -> PROVIDER.issuer());
  }

  @LocalServerPort private int port;

  @AfterAll
  void cleanUp() {
    if (PROVIDER != null) {
      PROVIDER.close();
    }
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

  private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static Optional<String> location(HttpResponse<String> response) {
    return response.headers().firstValue("Location");
  }

  @Test
  void authorizationCodeLoginCreatesAnApplicationAccount() throws Exception {
    Assumptions.assumeTrue(DATABASE != null, "未配置 IT_DB_* 测试数据库，跳过 PostgreSQL 集成测试");
    HttpClient client = browser();

    HttpResponse<String> anonymous = get(client, app("/api/v1/auth/me"));
    // OIDC 上下文：登录方式公开接口列出 enterprise SSO。
    HttpResponse<String> providers = get(client, app("/api/v1/auth/providers"));
    assertThat(providers.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<java.util.List<String>>read(providers.body(), "$.data.items[*].id"))
        .contains("enterprise");
    assertThat(anonymous.statusCode()).isEqualTo(401);

    HttpResponse<String> start = get(client, app("/oauth2/authorization/enterprise"));
    assertThat(start.statusCode()).isEqualTo(302);
    URI authorize = URI.create(location(start).orElseThrow());
    assertThat(authorize.getHost()).isEqualTo("127.0.0.1");
    assertThat(authorize.getPath()).isEqualTo("/authorize");

    HttpResponse<String> consent = get(client, authorize);
    assertThat(consent.statusCode()).isEqualTo(302);
    URI callback = URI.create(location(consent).orElseThrow());
    assertThat(callback.getPath()).isEqualTo("/login/oauth2/code/enterprise");
    assertThat(callback.getQuery()).contains("code=").contains("state=");

    HttpResponse<String> exchanged = get(client, callback);
    assertThat(exchanged.statusCode()).isEqualTo(302);
    assertThat(location(exchanged).orElseThrow()).endsWith("/");

    HttpResponse<String> me = get(client, app("/api/v1/auth/me"));
    assertThat(me.statusCode()).isEqualTo(200);
    String userId = JsonPath.read(me.body(), "$.data.userId");
    assertThat(userId).isNotBlank();
    assertThat(JsonPath.<java.util.List<String>>read(me.body(), "$.data.roles")).containsExactly("USER");

    // Logout must invalidate the server-side session, not just clear the cookie.
    String csrf =
        JsonPath.read(get(client, app("/api/v1/auth/csrf")).body(), "$.data.token");
    HttpResponse<String> logout =
        client.send(
            HttpRequest.newBuilder(app("/api/v1/auth/logout"))
                .header("X-CSRF-TOKEN", csrf)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(logout.statusCode()).isEqualTo(204);
    assertThat(
            logout.headers().allValues("Set-Cookie").stream()
                .anyMatch(cookie -> cookie.startsWith("QA_SESSION=;")))
        .isTrue();
    assertThat(get(client, app("/api/v1/auth/me")).statusCode()).isEqualTo(401);

    // A second login with the same subject resolves to the same account.
    HttpClient second = browser();
    get(second, app("/oauth2/authorization/enterprise"));
    HttpResponse<String> secondStart = get(second, app("/oauth2/authorization/enterprise"));
    HttpResponse<String> secondConsent = get(second, URI.create(location(secondStart).orElseThrow()));
    HttpResponse<String> secondCallback = get(second, URI.create(location(secondConsent).orElseThrow()));
    assertThat(secondCallback.statusCode()).isEqualTo(302);
    HttpResponse<String> secondMe = get(second, app("/api/v1/auth/me"));
    assertThat(secondMe.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<String>read(secondMe.body(), "$.data.userId")).isEqualTo(userId);
  }
}
