package dev.aiqa.answer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 词法候选与向量候选的混合排序。
 *
 * <p>词法命中给 1 分并按顺序线性衰减；向量命中按余弦相似度并相对本次最高分归一化。
 * 两者加权后取前 N：词法保证"精确词面"仍然靠前，向量把换了说法但语义相同的片段带进来。
 * 只有向量命中且余弦明显低于本次最高分的片段会被丢弃，避免长尾噪声挤进证据。
 */
public final class HybridRanker {

  private static final double LEXICAL_WEIGHT = 0.45;
  private static final double VECTOR_WEIGHT = 0.55;

  /** 纯向量候选的余弦至少要达到本次最高分的这个比例，才允许进入结果。 */
  private static final double VECTOR_FLOOR_RATIO = 0.75;

  private HybridRanker() {}

  /** 一个向量候选：与词法候选相同的内容，附带该文本块的向量。 */
  public record VectorCandidate<T>(T candidate, float[] embedding) {}

  public record Ranked<T>(T candidate, double lexical, double vector, double score) {}

  /**
   * @param lexical 词法候选，按相关度从高到低排列
   * @param vectors 向量候选，顺序无关
   * @param query 提问向量
   * @param limit 最多返回多少条
   */
  public static <T> List<T> merge(
      List<T> lexical,
      List<VectorCandidate<T>> vectors,
      float[] query,
      int limit,
      java.util.function.Function<T, Object> identity) {
    if (lexical.isEmpty() && vectors.isEmpty()) {
      return List.of();
    }
    Map<Object, Double> lexicalScore = new LinkedHashMap<>();
    for (int index = 0; index < lexical.size(); index++) {
      double score = lexical.size() == 1 ? 1 : 1 - (double) index / (lexical.size() - 1);
      lexicalScore.put(identity.apply(lexical.get(index)), score);
    }
    Map<Object, Double> cosine = new LinkedHashMap<>();
    double best = 0;
    for (VectorCandidate<T> vector : vectors) {
      double similarity = dev.aiqa.search.EmbeddingClient.cosine(query, vector.embedding());
      cosine.put(identity.apply(vector.candidate()), similarity);
      best = Math.max(best, similarity);
    }
    Map<Object, Ranked<T>> ranked = new LinkedHashMap<>();
    for (T candidate : lexical) {
      Object key = identity.apply(candidate);
      double lexicalPart = lexicalScore.getOrDefault(key, 0.0);
      double vectorPart = best > 0 ? Math.max(0, cosine.getOrDefault(key, 0.0)) / best : 0;
      ranked.put(key, new Ranked<>(candidate,
          lexicalPart, vectorPart,
          LEXICAL_WEIGHT * lexicalPart + VECTOR_WEIGHT * vectorPart));
    }
    for (VectorCandidate<T> vector : vectors) {
      Object key = identity.apply(vector.candidate());
      if (ranked.containsKey(key)) {
        continue;
      }
      double similarity = best > 0 ? Math.max(0, cosine.getOrDefault(key, 0.0)) / best : 0;
      if (similarity < VECTOR_FLOOR_RATIO) {
        continue;
      }
      ranked.put(key, new Ranked<>(vector.candidate(), 0, similarity,
          VECTOR_WEIGHT * similarity));
    }
    List<Ranked<T>> ordered = new ArrayList<>(ranked.values());
    ordered.sort((left, right) -> Double.compare(right.score(), left.score()));
    List<T> result = new ArrayList<>(Math.min(limit, ordered.size()));
    for (Ranked<T> item : ordered) {
      if (result.size() >= limit) {
        break;
      }
      result.add(item.candidate());
    }
    return result;
  }
}
