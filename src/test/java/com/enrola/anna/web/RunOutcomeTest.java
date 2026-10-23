package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A run's outcome from what it left behind; Maven's exit, only 0 or 1, is never an input. */
class RunOutcomeTest {

  @ParameterizedTest(name = "verdict {0}, gates {1}, report {2}: {3}")
  @CsvSource(
      nullValues = "-",
      value = {
        "PASS,    0, true,  GREEN", //     every gate green
        "FAIL,    3, true,  RED", //       hard and global failed
        "FAIL,    0, true,  RED", //       a trial crashed with the gates green
        "SKIPPED, 0, true,  SKIPPED", //   no key, or refused over the cap: run.sh exits 0 or 1
        "-,       2, true,  RED", //       the row did not land, the gates' file says red
        "-,       0, true,  GREEN", //     the row did not land, the gates' file says green
        "-,       0, false, ERROR", //     a gates' file and no report: the writer broke off
        "-,       -, true,  PLANNED", //   a dry run writes its plan and nothing else
        "-,       -, false, ERROR", //     crashed before writing anything
      })
  void readsTheGatesFileThenTheVerdict(
      String verdict, Integer gates, boolean reported, RunOutcome outcome) {
    assertThat(RunOutcome.of(new RunOutcome.Evidence(verdict, gates, reported))).isEqualTo(outcome);
  }
}
