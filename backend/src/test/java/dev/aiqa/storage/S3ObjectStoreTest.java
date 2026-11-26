package dev.aiqa.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the S3 adapter against an in-process path-style endpoint. It verifies the request shape
 * and byte round-trip, not AWS authentication or bucket policy.
 */
class S3ObjectStoreTest {

  private HttpServer server;
  private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String key = exchange.getRequestURI().getPath();
    switch (exchange.getRequestMethod()) {
      case "PUT" -> {
        try (InputStream body = exchange.getRequestBody()) {
          objects.put(key, decodeAwsChunked(body.readAllBytes()));
        }
        exchange.sendResponseHeaders(200, -1);
      }
      case "GET" -> {
        byte[] value = objects.get(key);
        if (value == null) {
          exchange.sendResponseHeaders(404, -1);
        } else {
          exchange.sendResponseHeaders(200, value.length);
          exchange.getResponseBody().write(value);
        }
      }
      case "DELETE" -> {
        objects.remove(key);
        exchange.sendResponseHeaders(204, -1);
      }
      default -> exchange.sendResponseHeaders(405, -1);
    }
    exchange.close();
  }

  /** The SDK signs payloads on plain HTTP using the aws-chunked framing; decode it. */
  private static byte[] decodeAwsChunked(byte[] body) {
    if (body.length == 0) {
      return body;
    }
    String text = new String(body, StandardCharsets.ISO_8859_1);
    if (!text.startsWith("\r\n") && !text.contains("chunk-signature=")) {
      return body;
    }
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    int cursor = 0;
    while (cursor < body.length) {
      int lineEnd = indexOf(body, "\r\n".getBytes(StandardCharsets.ISO_8859_1), cursor);
      if (lineEnd < 0) {
        break;
      }
      String header = new String(body, cursor, lineEnd - cursor, StandardCharsets.ISO_8859_1);
      int semicolon = header.indexOf(';');
      String sizeText = semicolon >= 0 ? header.substring(0, semicolon) : header;
      int size;
      try {
        size = Integer.parseInt(sizeText.trim(), 16);
      } catch (NumberFormatException ex) {
        return body;
      }
      cursor = lineEnd + 2;
      if (size == 0) {
        break;
      }
      out.write(body, cursor, Math.min(size, body.length - cursor));
      cursor += size + 2;
    }
    return out.toByteArray();
  }

  private static int indexOf(byte[] value, byte[] needle, int from) {
    outer:
    for (int start = from; start + needle.length <= value.length; start++) {
      for (int offset = 0; offset < needle.length; offset++) {
        if (value[start + offset] != needle[offset]) {
          continue outer;
        }
      }
      return start;
    }
    return -1;
  }

  @Test
  void writesReadsAndDeletesThroughTheS3Adapter() throws Exception {
    String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    S3ObjectStore store =
        new S3ObjectStore("test-bucket", "us-east-1", endpoint, "test-key", "test-secret", true);

    byte[] payload = "对象存储往返验证".getBytes(StandardCharsets.UTF_8);
    store.put("objects/roundtrip", new java.io.ByteArrayInputStream(payload), payload.length);

    try (InputStream read = store.get("objects/roundtrip")) {
      assertThat(read.readAllBytes()).isEqualTo(payload);
    }
    store.delete("objects/roundtrip");
    assertThat(objects).doesNotContainKey("/test-bucket/objects/roundtrip");
  }
}
