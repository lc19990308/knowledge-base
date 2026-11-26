package dev.aiqa.answer;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in used until an approved model gateway is configured. It never invents facts:
 * the answer is a citation-backed excerpt of the supplied evidence.
 *
 * <p>Token 数量按字符数估算并标注为估算值，用于打通用量上报链路；接入真实模型后由适配器返回实际用量。
 */
@Component
@ConditionalOnProperty(name = "aiqa.model.provider", havingValue = "fake", matchIfMissing = true)
public class FakeAnswerModel implements AnswerModel {

  /** 本地替身最多摘录的证据条数；真实模型会读到全部候选，这里多给几条避免漏掉另一个方面。 */
  private static final int MAX_EXCERPTS = 5;

  @Override
  public Rendered answer(String question, String template, List<EvidenceSource> evidence) {
    if (evidence.isEmpty()) {
      return new Rendered("", List.of(), estimate(question), 0L);
    }
    StringBuilder text = new StringBuilder("根据当前可访问且已发布的资料：");
    List<Integer> cited = new ArrayList<>();
    for (int index = 0; index < Math.min(MAX_EXCERPTS, evidence.size()); index++) {
      EvidenceSource source = evidence.get(index);
      String excerpt =
          source.text().length() > 240 ? source.text().substring(0, 240) + "…" : source.text();
      if (index > 0) {
        text.append('\n');
      }
      text.append(excerpt);
      cited.add(index);
    }
    return new Rendered(text.toString(), cited, estimate(question + text), estimate(text.toString()));
  }

  /** 粗略估算：中文约 1 字 1 token，英文按 4 字符 1 token 折算。 */
  private static long estimate(String value) {
    if (value == null || value.isEmpty()) {
      return 0L;
    }
    long cjk = value.codePoints().filter(cp -> Character.UnicodeScript.of(cp)
        == Character.UnicodeScript.HAN).count();
    long other = value.length() - cjk;
    return cjk + Math.max(1, other / 4);
  }
}
