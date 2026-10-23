package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A run's outcome from what it left behind; Maven's exit, only 0 or 1, is never an input. */
class RunOutcomeTest {

  @ParameterizedTest(name = "verdict {0}, exit code {1}, report {2}: {3}")
  @CsvSource(
      nullValues = "-",
      value = {
        "PASS,    0, true,  GREEN", //     every gate green
        "FAIL,    3, true,  RED", //       hard and global failed
        "FAIL,    0, true,  RED", //       a trial crashed with the gates green
        "SKIPPED, 8, true,  SKIPPED", //   no key, or refused over the cap
        "-,       8, true,  SKIPPED", //   nothing judged and the row did not land
        "-,       8, false, SKIPPED", //   the exit code alone says nothing judged
        "-,       9, true,  RED", //       a gate's bit wins over the nothing-judged bit
        "SKIPPED, 0, true,  SKIPPED", //   a run from before the nothing-ran bit
        "-,       2, true,  RED", //       the row did not land, the exit code says red
        "-,       0, true,  ERROR", //     exit 0 with no verdict anywhere is not green
        "-,       0, false, ERROR", //     an exit code and no report: the writer broke off
        "-,       -, true,  PLANNED", //   a dry run writes its plan and nothing else
        "-,       -, false, ERROR", //     crashed before writing anything
      })
  void readsTheGatesThenTheNothingJudgedBitThenTheVerdict(
      String verdict, Integer exitCode, boolean reported, RunOutcome outcome) {
    assertThat(RunOutcome.of(new RunOutcome.Evidence(verdict, exitCode, reported)))
        .isEqualTo(outcome);
  }
}
