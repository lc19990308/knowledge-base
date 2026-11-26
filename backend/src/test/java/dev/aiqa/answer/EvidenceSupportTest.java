package dev.aiqa.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 无依据拦截与覆盖优先重排的可回归规则。 */
class EvidenceSupportTest {

  private static List<String> tokens(String... values) {
    return List.of(values);
  }

  @Test
  void negatedOnlySubjectIsNotEvidence() {
    // 「不含早餐」只能说明不含，不能支撑"早餐能报多少"。
    var assessment = EvidenceSupport.assess(
        "差旅早餐可以报销多少元？",
        tokens("差", "旅", "早餐", "可以", "报销", "多少", "元"),
        List.of(
            new EvidenceSupport.Candidate("差旅报销管理办法", "返程后十五个工作日内提交报销申请。"),
            new EvidenceSupport.Candidate("住宿标准", "成都 B 档 460 元，含税，不含早餐。")));

    assertThat(assessment.sufficient()).isFalse();
    assertThat(assessment.negatedOnlyTerms()).contains("早餐");
  }

  @Test
  void negationAloneDoesNotBlockAYesNoQuestion() {
    // 「含税吗」不是在问金额，住宿片段本身就是可读依据。
    var assessment = EvidenceSupport.assess(
        "住宿限额是否含早餐？",
        tokens("住宿", "限额", "是否", "含", "早餐"),
        List.of(new EvidenceSupport.Candidate(
            "住宿限额", "住宿限额｜境内每人每晚\n表头上下文：城市：成都；上限（元）：460；计价口径：含税，不含早餐")));

    assertThat(assessment.sufficient()).isTrue();
  }

  @Test
  void unrelatedSubjectHasNoSupport() {
    var assessment = EvidenceSupport.assess(
        "绒冠蜥的保育周期是多少天？",
        tokens("绒", "冠", "蜥", "的", "保育", "周期", "是", "多少", "天"),
        List.of(new EvidenceSupport.Candidate("差旅办法", "返程后十五个工作日内提交报销申请。")));

    assertThat(assessment.sufficient()).isFalse();
    assertThat(assessment.positiveTerms()).isEmpty();
  }

  @Test
  void paraphraseWithAnyPositiveTermStillAnswers() {
    var assessment = EvidenceSupport.assess(
        "境内出差回来以后，最晚什么时候提交报销？返程当天算不算？",
        tokens("境内", "出差", "回来", "以后", "最晚", "什么", "时候", "提交", "报销", "返程", "当天"),
        List.of(new EvidenceSupport.Candidate(
            "差旅报销管理办法", "员工应在返程后 15 个工作日内提交差旅报销申请，返程当天不计入。")));

    assertThat(assessment.sufficient()).isTrue();
  }

  @Test
  void quotedEntityNameDoesNotCountAsEvidence() {
    // 公司名里的「设备」不能证明资料讲过"高原设备验收专项"。
    var assessment = EvidenceSupport.assess(
        "高原设备验收专项的驻点津贴是多少？",
        tokens("高原", "设备", "验收", "专项", "的", "驻", "点", "津贴", "是", "多少"),
        List.of(new EvidenceSupport.Candidate(
            "差旅住宿标准",
            "模拟企业「北岸设备服务」测试资料，仅用于 E2E。\n## §2.2 核算规则\n表中金额不包含交通费用。")));

    assertThat(assessment.sufficient()).isFalse();
  }

  @Test
  void colloquialParaphraseStillFindsTheClause() {
    // 「出完差回来交单子」的实词与原文不同，但与资料共享多个词，不能据此判无依据。
    var assessment = EvidenceSupport.assess(
        "我出完差回来了，几天之内得把单子交上去？",
        tokens("我", "出", "完", "差", "回来", "了", "几", "天", "之内", "得", "把", "单子", "交", "上", "去"),
        List.of(new EvidenceSupport.Candidate(
            "差旅报销管理办法", "员工应在返程后 15 个工作日内提交差旅报销申请，返程当天不计入。")));

    assertThat(assessment.sufficient()).isTrue();
  }

  @Test
  void rerankBringsEachAspectOfACompoundQuestionForward() {
    String lodging = "城市：成都；档位：B 档；上限（元）：460；每人每晚，含税。";
    String lodgingAgain = "城市：成都；档位：B 档；上限（元）：460；每人每晚，含税，不含早餐。";
    String deadline = "返程后 15 个工作日内提交报销申请，返程当天不计。";
    List<String> chunks = List.of(
        lodging, "住宿限额表头：城市 档位 上限（元）", lodgingAgain, deadline);

    var ordered = EvidenceSupport.rerank(
        tokens("成都", "出差", "回来", "报销", "期限", "每晚", "住宿", "限额"), chunks, text -> text);

    // 第一片仍是覆盖最多的住宿证据；报销这条新方面要排在同一方面的重复片段之前。
    assertThat(ordered.get(0)).isEqualTo(lodging);
    assertThat(ordered.indexOf(deadline)).isLessThan(ordered.indexOf(lodgingAgain));
  }

  @Test
  void singleCandidateKeepsItsOrder() {
    var ordered = EvidenceSupport.rerank(
        tokens("成都", "住宿"), List.of("成都 460 元"), text -> text);
    assertThat(ordered).containsExactly("成都 460 元");
  }
}
