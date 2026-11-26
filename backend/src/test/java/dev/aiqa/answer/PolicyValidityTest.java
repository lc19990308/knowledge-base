package dev.aiqa.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 政策有效性：快照状态 + 生效区间 → 当前结论。 */
class PolicyValidityTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

  @Test
  void effectivePolicyIsCurrent() {
    assertThat(PolicyValidity.evaluate("EFFECTIVE", null, null, TODAY))
        .isEqualTo(PolicyValidity.State.EFFECTIVE);
    assertThat(PolicyValidity.evaluate("EFFECTIVE", TODAY, TODAY.plusDays(10), TODAY))
        .isEqualTo(PolicyValidity.State.EFFECTIVE);
  }

  @Test
  void passedEndDateIsExpired() {
    assertThat(PolicyValidity.evaluate("EFFECTIVE", null, TODAY.minusDays(1), TODAY))
        .isEqualTo(PolicyValidity.State.EXPIRED);
    assertThat(PolicyValidity.evaluate("EXPIRED", null, null, TODAY))
        .isEqualTo(PolicyValidity.State.EXPIRED);
  }

  @Test
  void futureStartOrUnknownStateIsUncertain() {
    assertThat(PolicyValidity.evaluate("EFFECTIVE", TODAY.plusDays(1), null, TODAY))
        .isEqualTo(PolicyValidity.State.UNCERTAIN);
    assertThat(PolicyValidity.evaluate("UNKNOWN", null, null, TODAY))
        .isEqualTo(PolicyValidity.State.UNCERTAIN);
  }

  @Test
  void endDateIsInclusive() {
    assertThat(PolicyValidity.evaluate("EFFECTIVE", null, TODAY, TODAY))
        .isEqualTo(PolicyValidity.State.EFFECTIVE);
  }
}
