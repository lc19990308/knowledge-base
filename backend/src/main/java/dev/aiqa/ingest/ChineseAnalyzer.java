package dev.aiqa.ingest;

import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.springframework.stereotype.Component;

/** Single tokenizer configuration shared by indexing and querying. */
@Component
public class ChineseAnalyzer {

  private static final Analyzer ANALYZER = new SmartChineseAnalyzer();

  public List<String> tokens(String text) {
    try (var stream = ANALYZER.tokenStream("text", text)) {
      stream.reset();
      var attributes = stream.getAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
      List<String> tokens = new java.util.ArrayList<>();
      while (stream.incrementToken()) {
        String token = attributes.toString().strip();
        if (!token.isEmpty()) {
          tokens.add(token);
        }
      }
      stream.end();
      return tokens;
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("分词失败", ex);
    }
  }

  public String searchVectorLiteral(String text) {
    List<String> tokens = tokens(text);
    if (tokens.isEmpty()) {
      return "";
    }
    return String.join(" ", tokens.stream().map(token -> token.replaceAll("[\\\\'\\s]", "")).toList());
  }
}
