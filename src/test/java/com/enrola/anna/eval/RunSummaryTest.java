package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A run's verdict and exit code: the gates' bits, and 8 when no trial was judged. */
class RunSummaryTest {

  static final Scenario.Turn CORE =
      ScenarioFixtures.turn("turn-core", "hi", Scenario.Expect.NONE, "core");
  static final Scenario.Turn SIDE = ScenarioFixtures.turn("turn-side", "hi", Scenario.Expect.NONE);

  @ParameterizedTest(name = "core {0}, side {1}, threshold {2}: {3}, exit {4}")
  @CsvSource({
    "SKIPPED_NO_KEY, SKIPPED_NO_KEY, 0.2, SKIPPED, 8", // no key
    "SKIPPED_BUDGET, SKIPPED_BUDGET, 0.2, SKIPPED, 8", // the estimate refused over the cap
    "PASS,           SKIPPED_KIND,   0.2, PASS,    0", // one judged: the skip is the report's
    "PASS,           SKIPPED_BUDGET, 0.2, PASS,    0", // the cap reached mid-run
    "INVALID,        INVALID,        1.0, SKIPPED, 8", // the gates allow it; nothing was judged
    "INVALID,        INVALID,        0.2, FAIL,    4", // the validity gate fails first
    "FAIL,           PASS,           0.2, FAIL,    2", // a core trial failed
  })
  void nothingJudgedAddsEightToTheGates(
      TrialStatus core,
      TrialStatus side,
      double threshold,
      RunSummary.Verdict verdict,
      int exitCode) {
    List<Trial> trials =
        List.of(ScenarioFixtures.trial(CORE, 1, core), ScenarioFixtures.trial(SIDE, 1, side));
    var summary = new RunSummary(trials, 2, Gates.evaluate(trials, List.of(CORE, SIDE), threshold));

    assertThat(summary.verdict()).isEqualTo(verdict);
    assertThat(summary.exitCode()).isEqualTo(exitCode);
  }
}
