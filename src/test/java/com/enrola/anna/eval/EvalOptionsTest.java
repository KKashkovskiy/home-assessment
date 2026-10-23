package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Options refused before anything runs. */
class EvalOptionsTest {

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1"})
  void aCapIsPositive(String dollars) {
    assertThatThrownBy(() -> options(new BigDecimal(dollars), 1, 0.2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("eval.budgetUsd must be a positive number of dollars");
  }

  @Test
  void aRunHasATrialAtLeast() {
    assertThatThrownBy(() -> options(null, 0, 0.2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("eval.trials must be a whole number from 1");
  }

  @ParameterizedTest
  @ValueSource(doubles = {-0.1, 1.5, Double.NaN})
  void theValidityThresholdIsAShare(double threshold) {
    assertThatThrownBy(() -> options(null, 1, threshold))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("eval.validityThreshold must be a number from 0 to 1");
  }

  @Test
  void judgesAreAdvisoryOrNone() {
    assertThat(EvalOptions.judges("advisory")).isTrue();
    assertThat(EvalOptions.judges("none")).isFalse();
    assertThatThrownBy(() -> EvalOptions.judges("gating"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("eval.judges must be advisory or none");
  }

  private static EvalOptions options(BigDecimal budgetUsd, int trials, double threshold) {
    return new EvalOptions(
        "r",
        Selection.ALL,
        new RunMode.Offline(),
        budgetUsd,
        trials,
        threshold,
        true,
        Path.of("evals/runs/r"),
        "jdbc:x");
  }
}
