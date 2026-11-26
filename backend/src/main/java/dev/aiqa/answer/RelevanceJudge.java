package dev.aiqa.answer;

import java.util.List;

/**
 * 语义相关性判定：候选证据能否支撑这次提问。
 *
 * <p>词法门控只能判断"词是否出现、是否被否定"，判断不了"被问的对象在资料里根本没有"。
 * 例如「差旅早餐可以报销多少元」会命中报销时限条款。这里把这一步交给已配置的模型：
 * 模型认为资料不能支撑时，回答按无依据返回且不写引用。
 *
 * <p>判定不可用时按<b>失败开放</b>处理（继续回答），避免判定服务抖动把正常问答一起拦掉。
 */
public interface RelevanceJudge {

  /** 判定使用的模型网关；与回答所用配置一致。 */
  record Model(String protocol, String baseUrl, String modelName, String credentialRef) {}

  /**
   * @param sufficient 是否足以支撑回答
   * @param available 判定是否真正执行（false 表示跳过或失败）
   * @param reason 便于日志与测试的简短原因，不直接展示给用户
   */
  record Verdict(
      boolean sufficient, boolean available, String reason, Long inputTokens, Long outputTokens) {

    public static Verdict pass(String reason) {
      return new Verdict(true, true, reason, null, null);
    }

    public static Verdict unavailable(String reason) {
      return new Verdict(true, false, reason, null, null);
    }
  }

  Verdict judge(Model model, String question, List<String> evidenceTexts);
}
