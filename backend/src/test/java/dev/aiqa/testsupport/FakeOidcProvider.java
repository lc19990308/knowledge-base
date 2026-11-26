package dev.aiqa.testsupport;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal OIDC provider for integration tests: discovery, authorization code redirect, token
 * exchange with a signed id_token, and JWKS. Not a security component; test only.
 */
public final class FakeOidcProvider implements AutoCloseable {

  private final HttpServer server;
  private final RSAKey signingKey;
  private final String issuer;
  private final String clientId;
  private final String clientSecret;
  private final Map<String, Pending> codes = new ConcurrentHashMap<>();

  private record Pending(String nonce, String subject) {}

  private FakeOidcProvider(String clientId, String clientSecret) throws Exception {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    this.issuer = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/.well-known/openid-configuration", this::discovery);
    server.createContext("/jwks", this::jwks);
    server.createContext("/authorize", this::authorize);
    server.createContext("/token", this::token);
    server.start();
  }

  public static FakeOidcProvider start(String clientId, String clientSecret) throws Exception {
    return new FakeOidcProvider(clientId, clientSecret);
  }

  public String issuer() {
    return issuer;
  }

  private void discovery(HttpExchange exchange) throws IOException {
    String body =
        """
        {"issuer":"%s","authorization_endpoint":"%s/authorize","token_endpoint":"%s/token",\
        "jwks_uri":"%s/jwks","response_types_supported":["code"],"subject_types_supported":["public"],\
        "id_token_signing_alg_values_supported":["RS256"],"scopes_supported":["openid","profile"],\
        "token_endpoint_auth_methods_supported":["client_secret_basic","client_secret_post"]}
        """
            .formatted(issuer, issuer, issuer, issuer);
    respond(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
  }

  private void jwks(HttpExchange exchange) throws IOException {
    String body = new JWKSet(signingKey.toPublicJWK()).toString();
    respond(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
  }

  private void authorize(HttpExchange exchange) throws IOException {
    Map<String, String> query = parse(exchange.getRequestURI().getRawQuery());
    if (!clientId.equals(query.get("client_id"))) {
      respond(exchange, 400, "text/plain", "unknown client".getBytes(StandardCharsets.UTF_8));
      return;
    }
    String code = UUID.randomUUID().toString();
    codes.put(code, new Pending(query.get("nonce"), "oidc-user-1"));
    String location =
        query.get("redirect_uri")
            + "?code="
            + URLEncoder.encode(code, StandardCharsets.UTF_8)
            + "&state="
            + URLEncoder.encode(query.getOrDefault("state", ""), StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Location", location);
    exchange.sendResponseHeaders(302, -1);
    exchange.close();
  }

  private void token(HttpExchange exchange) throws IOException {
    Map<String, String> form = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    String code = form.get("code");
    Pending pending = code == null ? null : codes.remove(code);
    if (pending == null) {
      respond(exchange, 400, "application/json",
          "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8));
      return;
    }
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(pending.subject())
            .audience(clientId)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(600)))
            .claim("nonce", pending.nonce())
            .claim("name", "OIDC 测试用户")
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID())
                .type(JOSEObjectType.JWT)
                .build(),
            claims);
    try {
      jwt.sign(new RSASSASigner(signingKey));
    } catch (Exception ex) {
      throw new IOException(ex);
    }
    String body =
        """
        {"access_token":"test-access-token","token_type":"Bearer","expires_in":3600,\
        "scope":"openid profile","id_token":"%s"}
        """
            .formatted(jwt.serialize());
    respond(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, String> parse(String query) {
    Map<String, String> values = new HashMap<>();
    if (query == null || query.isBlank()) {
      return values;
    }
    for (String pair : query.split("&")) {
      int equals = pair.indexOf('=');
      if (equals < 0) {
        continue;
      }
      values.put(
          URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
    }
    return values;
  }

  private static void respond(HttpExchange exchange, int status, String type, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", type);
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
