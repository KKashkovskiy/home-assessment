package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Options refused before anything runs. */
class EvalOptionsTest {

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1"})
  void aCapIsPositive(String dollars) {
    assertThatThrownBy(() -> options(new BigDecimal(dollars)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("eval.budgetUsd must be a positive number of dollars");
  }

  private static EvalOptions options(BigDecimal budgetUsd) {
    return new EvalOptions(
        "r", Selection.ALL, new RunMode.Offline(), budgetUsd, Path.of("evals/runs/r"), "jdbc:x");
  }
}
