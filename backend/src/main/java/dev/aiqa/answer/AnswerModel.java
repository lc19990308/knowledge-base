package dev.aiqa.answer;

import java.util.List;

public interface AnswerModel {

  /** 证据来源；validity 为快照政策状态与生效区间折算出的当前有效性。 */
  record EvidenceSource(
      String spaceId, String publicationId, long epoch, String documentId, String documentName,
      String text, String validity) {}

  /** 用量可为 null：模型未上报时保持「未上报」，不记为零。 */
  record Rendered(
      String text, List<Integer> citedEvidenceIndexes, Long inputTokens, Long outputTokens) {

    public Rendered(String text, List<Integer> cited) {
      this(text, cited, null, null);
    }
  }

  Rendered answer(String question, String template, List<EvidenceSource> evidence);
}
