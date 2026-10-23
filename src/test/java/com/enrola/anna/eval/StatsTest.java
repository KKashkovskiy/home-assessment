package com.enrola.anna.eval;

import static com.enrola.anna.eval.TrialStatus.FAIL;
import static com.enrola.anna.eval.TrialStatus.INVALID;
import static com.enrola.anna.eval.TrialStatus.PASS;
import static com.enrola.anna.eval.TrialStatus.SKIPPED_BUDGET;
import static com.enrola.anna.eval.TrialStatus.SKIPPED_NO_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** pass^k and the Wilson interval against worked examples. */
class StatsTest {

  @Test
  void passKAllClean() {
    assertThat(Stats.passK(List.of(trials(PASS, PASS, PASS), trials(PASS, PASS, PASS))))
        .isEqualTo(1.0);
  }

  @Test
  void passKOneFailAnywhere() {
    assertThat(Stats.passK(List.of(trials(PASS, PASS, FAIL), trials(PASS, PASS, PASS))))
        .isEqualTo(0.5);
  }

  /** B has no judged trial, so it leaves the denominator; nothing judged at all is NaN. */
  @Test
  void passKIgnoresInvalidAndSkipped() {
    assertThat(Stats.passK(List.of(trials(PASS, INVALID), trials(SKIPPED_BUDGET)))).isEqualTo(1.0);
    assertThat(Stats.passK(List.of(trials(SKIPPED_NO_KEY)))).isNaN();
  }

  /** p = 0.8, z² = 3.8416: centre 0.7167, half-width 0.2266. */
  @Test
  void wilsonEightOfTen() {
    Stats.Interval interval = Stats.wilson(8, 10, 1.96);

    assertThat(interval.low()).isCloseTo(0.490, within(0.001));
    assertThat(interval.high()).isCloseTo(0.943, within(0.001));
  }

  @Test
  void wilsonZeroN() {
    assertThat(Stats.wilson(0, 0, 1.96)).isEqualTo(new Stats.Interval(Double.NaN, Double.NaN));
  }

  @Test
  void wilsonAllPass() {
    Stats.Interval interval = Stats.wilson(10, 10, 1.96);

    assertThat(interval.high()).isEqualTo(1.0);
    assertThat(interval.low()).isCloseTo(0.722, within(0.001));
  }

  /** One scenario's trials, numbered from 1. */
  private static List<Trial> trials(TrialStatus... statuses) {
    Scenario scenario = ScenarioFixtures.turn("turn-x", "hi", Scenario.Expect.NONE);
    return IntStream.range(0, statuses.length)
        .mapToObj(i -> ScenarioFixtures.trial(scenario, i + 1, statuses[i]))
        .toList();
  }
}
