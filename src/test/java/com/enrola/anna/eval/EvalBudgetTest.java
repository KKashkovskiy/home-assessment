package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.PriceTable;
import com.enrola.anna.config.Settings;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The preflight arithmetic and the cap during a run. */
class EvalBudgetTest {

  /** 6,000 in at $5 per million plus 400 out at $50 per million: $0.05 a typical call. */
  static final PriceTable FIVE_CENTS =
      new PriceTable(
          Map.of(
              "m", new Settings.Price(new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("50"))));

  @Test
  void preflightRefusesAnEstimateOverTheCap() {
    BigDecimal estimate =
        new EvalBudget(new BigDecimal("2"), FIVE_CENTS, "m").estimate(turns(41), 1);

    assertThat(estimate).isEqualByComparingTo("4.10");
    assertThat(new EvalBudget(new BigDecimal("2"), FIVE_CENTS, "m").allows(estimate)).isFalse();
    assertThat(new EvalBudget(new BigDecimal("5"), FIVE_CENTS, "m").allows(estimate)).isTrue();
  }

  /** Two turns at two calls and a story at fourteen, three trials each: 54 calls at $0.05. */
  @Test
  void eachKindIsEstimatedPerTrial() {
    List<Scenario> mixed =
        Stream.concat(turns(2).stream(), Stream.of(ScenarioFixtures.story("story-x"))).toList();

    assertThat(new EvalBudget(BigDecimal.ONE, FIVE_CENTS, "m").estimate(mixed, 3))
        .isEqualByComparingTo("2.70");
  }

  /** At $0.08 spent of $0.10, one more $0.05 call would pass the cap, so the next trial skips. */
  @Test
  void capReachedMidRunStopsTheNextCall() {
    var budget = new EvalBudget(new BigDecimal("0.10"), FIVE_CENTS, "m");

    budget.spend(new BigDecimal("0.04"));
    assertThat(budget.allowsAnotherCall()).isTrue();
    budget.spend(new BigDecimal("0.04"));
    assertThat(budget.allowsAnotherCall()).isFalse();
  }

  /** A story's own spend so far counts before the ledger has it. */
  @Test
  void aRunningStoryCountsWhatItSpent() {
    var budget = new EvalBudget(new BigDecimal("0.10"), FIVE_CENTS, "m");

    assertThat(budget.allowsAnotherCall(new BigDecimal("0.05"))).isTrue();
    assertThat(budget.allowsAnotherCall(new BigDecimal("0.06"))).isFalse();
  }

  @Test
  void anUnpricedModelIsNeverRefused() {
    assertThat(new EvalBudget(BigDecimal.ONE, FIVE_CENTS, "other").estimate(turns(3), 1))
        .isEqualByComparingTo("0");
  }

  static List<Scenario> turns(int count) {
    return IntStream.range(0, count)
        .<Scenario>mapToObj(i -> ScenarioFixtures.turn("turn-" + i, "hi", Scenario.Expect.NONE))
        .toList();
  }
}
