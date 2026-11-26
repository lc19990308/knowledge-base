package dev.aiqa.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 混合排序：词法优先、向量补充、噪声截断。 */
class HybridRankerTest {

  private record Doc(UUID id, String label) {}

  private static Doc doc(String label) {
    return new Doc(UUID.randomUUID(), label);
  }

  private static float[] vector(float x, float y) {
    return new float[] {x, y};
  }

  @Test
  void withoutVectorsLexicalOrderIsKept() {
    Doc first = doc("a");
    Doc second = doc("b");
    List<Doc> merged = HybridRanker.merge(
        List.of(first, second), List.of(), vector(1, 0), 20, Doc::id);
    assertThat(merged).containsExactly(first, second);
  }

  @Test
  void vectorCandidateNearTheBestEntersResults() {
    Doc lexical = doc("lexical");
    Doc paraphrase = doc("paraphrase");
    List<HybridRanker.VectorCandidate<Doc>> vectors = List.of(
        new HybridRanker.VectorCandidate<>(lexical, vector(1, 0)),
        new HybridRanker.VectorCandidate<>(paraphrase, vector(0.95f, 0.05f)));

    List<Doc> merged = HybridRanker.merge(
        List.of(lexical), vectors, vector(1, 0), 20, Doc::id);

    assertThat(merged).hasSize(2).contains(paraphrase);
  }

  @Test
  void distantVectorCandidateIsDropped() {
    Doc lexical = doc("lexical");
    Doc noise = doc("noise");
    List<HybridRanker.VectorCandidate<Doc>> vectors = List.of(
        new HybridRanker.VectorCandidate<>(lexical, vector(1, 0)),
        new HybridRanker.VectorCandidate<>(noise, vector(0.2f, 0.98f)));

    List<Doc> merged = HybridRanker.merge(
        List.of(lexical), vectors, vector(1, 0), 20, Doc::id);

    assertThat(merged).containsExactly(lexical);
  }

  @Test
  void sameChunkAppearsOnceAndKeepsLexicalCredit() {
    Doc only = doc("only");
    List<HybridRanker.VectorCandidate<Doc>> vectors =
        List.of(new HybridRanker.VectorCandidate<>(only, vector(1, 0)));

    List<Doc> merged = HybridRanker.merge(List.of(only), vectors, vector(1, 0), 20, Doc::id);

    assertThat(merged).containsExactly(only);
  }

  @Test
  void limitCapsResultAndVectorOnlyCanOutrankWeakLexical() {
    Doc strong = doc("strong");
    Doc weak = doc("weak");
    Doc semantic = doc("semantic");
    List<HybridRanker.VectorCandidate<Doc>> vectors = List.of(
        new HybridRanker.VectorCandidate<>(strong, vector(1, 0)),
        new HybridRanker.VectorCandidate<>(semantic, vector(0.99f, 0.01f)),
        new HybridRanker.VectorCandidate<>(weak, vector(0.1f, 0.99f)));

    List<Doc> merged = HybridRanker.merge(
        List.of(strong, weak), vectors, vector(1, 0), 2, Doc::id);

    assertThat(merged).hasSize(2).contains(strong, semantic);
  }
}
