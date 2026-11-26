package dev.aiqa.answer;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 政策有效性判定：以快照冻结的政策状态与生效区间为准，结合当前北京时间。
 *
 * <p>规则（与 PRD R03 一致）：
 *
 * <ul>
 *   <li>已过有效期末日 → EXPIRED，不得作为现行依据
 *   <li>未到生效日，或状态为 UNKNOWN → UNCERTAIN，标注「有效性待核实」
 *   <li>其余按审核通过的 policyState 取 EFFECTIVE
 * </ul>
 */
public final class PolicyValidity {

  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private PolicyValidity() {}

  public enum State {
    EFFECTIVE,
    EXPIRED,
    UNCERTAIN
  }

  public static State evaluate(String policyState, LocalDate effectiveFrom, LocalDate effectiveUntil) {
    return evaluate(policyState, effectiveFrom, effectiveUntil, LocalDate.now(ZONE));
  }

  public static State evaluate(
      String policyState, LocalDate effectiveFrom, LocalDate effectiveUntil, LocalDate today) {
    if ("EXPIRED".equals(policyState)) {
      return State.EXPIRED;
    }
    if (effectiveUntil != null && today.isAfter(effectiveUntil)) {
      return State.EXPIRED;
    }
    if (effectiveFrom != null && today.isBefore(effectiveFrom)) {
      return State.UNCERTAIN;
    }
    if (!"EFFECTIVE".equals(policyState)) {
      return State.UNCERTAIN;
    }
    return State.EFFECTIVE;
  }
}
