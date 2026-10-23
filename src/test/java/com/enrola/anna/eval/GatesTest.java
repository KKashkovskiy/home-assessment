package com.enrola.anna.eval;

import static com.enrola.anna.eval.ScenarioFixtures.trial;
import static com.enrola.anna.eval.TrialStatus.FAIL;
import static com.enrola.anna.eval.TrialStatus.INVALID;
import static com.enrola.anna.eval.TrialStatus.PASS;
import static com.enrola.anna.eval.TrialStatus.SKIPPED_BUDGET;
import static com.enrola.anna.eval.TrialStatus.SKIPPED_NO_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Which failures make a run red, and the exit code they add up to. */
class GatesTest {

  static final Scenario.Turn CORE =
      ScenarioFixtures.turn("turn-core", "hi", Scenario.Expect.NONE, "core");
  static final Scenario.Turn SIDE =
      ScenarioFixtures.turn("turn-side", "hi", Scenario.Expect.NONE, "scheduling");
  static final List<Scenario> SELECTED = List.of(CORE, SIDE);

  static final Check LONG = Check.hard("length", false, "reply is 330 chars, limit 320");
  static final Check WRONG_INTENT =
      Check.expectation("lead_intent", false, "expected=OBJECTION seen=ENGAGED");

  @Test
  void hardFailsOnAnySuiteWideCheck() {
    List<Trial> trials =
        List.of(
            trial(SIDE, 1, PASS, Check.hard("length", true, "80 chars")),
            trial(SIDE, 2, FAIL, LONG));

    GateResult gates = Gates.evaluate(trials, SELECTED, 0.2);

    assertThat(List.of(gates.hard(), gates.global(), gates.validity()))
        .containsExactly(false, true, true);
    assertThat(gates.exitCode()).isEqualTo(1);
    assertThat(gates.hardFailures())
        .singleElement()
        .satisfies(failure -> assertThat(failure.trial().label()).isEqualTo("turn-side#2"));
  }

  @Test
  void hardIgnoresExpectationFailures() {
    assertThat(Gates.evaluate(List.of(trial(SIDE, 1, FAIL, WRONG_INTENT)), SELECTED, 0.2).hard())
        .isTrue();
  }

  /** Replies an INVALID or a budget-cut trial sent were really sent, so their hard checks count. */
  @ParameterizedTest
  @EnumSource(
      value = TrialStatus.class,
      names = {"INVALID", "SKIPPED_BUDGET"})
  void hardCountsEveryRecordedTrial(TrialStatus status) {
    assertThat(Gates.evaluate(List.of(trial(SIDE, 1, status, LONG)), SELECTED, 1).hard()).isFalse();
  }

  /** An INVALID trial of a core scenario says nothing about Anna, so global holds. */
  @Test
  void globalIgnoresInvalidTrials() {
    assertThat(Gates.evaluate(List.of(trial(CORE, 1, INVALID)), SELECTED, 1).global()).isTrue();
  }

  @Test
  void globalFailsOnCoreOnly() {
    assertThat(Gates.evaluate(List.of(trial(SIDE, 1, FAIL, WRONG_INTENT)), SELECTED, 0.2).global())
        .isTrue();

    GateResult gates = Gates.evaluate(List.of(trial(CORE, 1, FAIL, WRONG_INTENT)), SELECTED, 0.2);

    assertThat(gates.global()).isFalse();
    assertThat(gates.exitCode()).isEqualTo(2);
  }

  /** Two INVALID of ten trials run is 20%: within a threshold at or above it, over one below. */
  @ParameterizedTest
  @CsvSource({"0.3, true", "0.2, true", "0.1, false"})
  void validityThreshold(double threshold, boolean valid) {
    List<Trial> trials = new ArrayList<>();
    IntStream.rangeClosed(1, 8).forEach(k -> trials.add(trial(SIDE, k, PASS)));
    trials.add(trial(SIDE, 9, INVALID));
    trials.add(trial(SIDE, 10, INVALID));
    trials.add(trial(SIDE, 11, SKIPPED_BUDGET));

    GateResult gates = Gates.evaluate(trials, SELECTED, threshold);

    assertThat(gates.invalidShare()).isEqualTo(0.2);
    assertThat(gates.validity()).isEqualTo(valid);
    assertThat(gates.exitCode()).isEqualTo(valid ? 0 : 4);
  }

  @Test
  void allSkippedIsGreen() {
    List<Trial> trials = List.of(trial(CORE, 1, SKIPPED_NO_KEY), trial(SIDE, 1, SKIPPED_NO_KEY));

    assertThat(Gates.evaluate(trials, SELECTED, 0.2).exitCode()).isZero();
  }

  @Test
  void combinedCodes() {
    GateResult gates = Gates.evaluate(List.of(trial(CORE, 1, FAIL, LONG)), SELECTED, 0.2);

    assertThat(gates.exitCode()).isEqualTo(3);
  }
}
