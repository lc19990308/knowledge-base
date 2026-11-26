package dev.aiqa.search;

import dev.aiqa.ingest.ChineseAnalyzer;
import org.springframework.stereotype.Component;

@Component
public class KeywordSearch {
  public static final String MATCH = "c.search_vector @@ to_tsquery('simple', :query)";
  public static final String SCORE = "ts_rank(c.search_vector, to_tsquery('simple', :query))";

  private final ChineseAnalyzer analyzer;

  public KeywordSearch(ChineseAnalyzer analyzer) {
    this.analyzer = analyzer;
  }

  public String query(String text) {
    return String.join(" | ", tokens(text));
  }

  /** 与索引同一套分词结果，供证据充分性判定复用，避免两处口径不一致。 */
  public java.util.List<String> tokens(String text) {
    return analyzer.tokens(text).stream()
        .filter(token -> token.codePoints().allMatch(Character::isLetterOrDigit))
        .filter(token -> !token.isEmpty())
        .distinct()
        .toList();
  }
}
