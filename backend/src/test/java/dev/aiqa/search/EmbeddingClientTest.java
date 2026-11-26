package dev.aiqa.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 嵌入客户端的解析、字面量与余弦；不访问网络。 */
class EmbeddingClientTest {

  private EmbeddingClient client(boolean enabled) {
    return new EmbeddingClient(enabled, "http://127.0.0.1:9/v1", "test-embedding",
        Duration.ofSeconds(2));
  }

  @Test
  void disabledClientNeverCallsOrBlocks() {
    assertThat(client(false).embed("差旅住宿限额")).isNull();
    assertThat(client(false).embedAll(List.of("a", "b"))).isEmpty();
    assertThat(client(false).enabled()).isFalse();
  }

  @Test
  void parsesOpenAiCompatibleEmbeddingResponse() {
    String body = """
        {"data":[{"embedding":[0.1,-0.2,0.3],"index":0},{"embedding":[1,0,0],"index":1}],
         "model":"test-embedding","usage":{"prompt_tokens":4,"total_tokens":4}}
        """;
    List<float[]> vectors = client(true).parse(body);
    assertThat(vectors).hasSize(2);
    assertThat(vectors.get(0)).hasSize(3);
    assertThat(vectors.get(0)[1]).isEqualTo(-0.2f);
    assertThat(vectors.get(1)[0]).isEqualTo(1f);
  }

  @Test
  void malformedResponseYieldsNothing() {
    assertThat(client(true).parse("{\"error\":\"boom\"}")).isEmpty();
    assertThat(client(true).parse("{\"data\":[{\"object\":\"x\"}]}")).isEmpty();
  }

  @Test
  void cosineIsBoundedAndHandlesDegenerateInput() {
    assertThat(EmbeddingClient.cosine(new float[] {1, 0}, new float[] {1, 0})).isEqualTo(1.0);
    assertThat(EmbeddingClient.cosine(new float[] {1, 0}, new float[] {0, 1})).isEqualTo(0.0);
    assertThat(EmbeddingClient.cosine(new float[] {1, 0}, new float[] {0, 1, 0})).isEqualTo(0.0);
    assertThat(EmbeddingClient.cosine(null, new float[] {1})).isEqualTo(0.0);
    assertThat(EmbeddingClient.cosine(new float[] {0, 0}, new float[] {1, 1})).isEqualTo(0.0);
  }

  @Test
  void literalMatchesPostgresRealArraySyntax() {
    assertThat(EmbeddingClient.literal(new float[] {0.5f, -1f})).isEqualTo("{0.500000,-1.000000}");
    assertThat(EmbeddingClient.literal(null)).isNull();
    assertThat(EmbeddingClient.literal(new float[0])).isNull();
  }
}
