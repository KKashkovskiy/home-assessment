package com.enrola.anna.eval;

import static com.enrola.anna.eval.ScenarioFixtures.trial;
import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.eval.JudgeResult.Verdict;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Advisory means advisory: a judge answer, every one failing or every one passing, cannot change
 * hard, global, validity or the exit code.
 */
class GatesUnchangedTest {

  static final List<Scenario> CORE =
      IntStream.rangeClosed(1, 12)
          .<Scenario>mapToObj(
              index ->
                  ScenarioFixtures.turn("turn-core-" + index, "hi", Scenario.Expect.NONE, "core"))
          .toList();

  @Test
  void allJudgesFailingStillExitsZero() {
    List<Trial> trials = judged(passing(), Verdict.FAIL);

    GateResult gates = Gates.evaluate(trials, CORE, 0.2);

    assertThat(List.of(gates.hard(), gates.global(), gates.validity()))
        .containsExactly(true, true, true);
    assertThat(gates.exitCode()).isZero();
  }

  @Test
  void allJudgesPassingCannotRescueAFailedCheck() {
    List<Trial> trials = new ArrayList<>(passing());
    trials.set(
        0, trial(CORE.getFirst(), 1, TrialStatus.FAIL, Check.hard("length", false, "341 chars")));

    GateResult gates = Gates.evaluate(judged(trials, Verdict.PASS), CORE, 0.2);

    assertThat(List.of(gates.hard(), gates.global())).containsExactly(false, false);
    assertThat(gates.exitCode()).isEqualTo(3);
  }

  /** Twelve core trials, every check passing. */
  private static List<Trial> passing() {
    return CORE.stream()
        .map(scenario -> trial(scenario, 1, TrialStatus.PASS, Check.hard("length", true, "80")))
        .toList();
  }

  private static List<Trial> judged(List<Trial> trials, Verdict verdict) {
    return trials.stream()
        .map(
            trial ->
                trial.withJudges(
                    List.of(
                        new JudgeResult(
                            "J-SMS-STYLE",
                            trial.scenarioId(),
                            trial.trialNo(),
                            1,
                            verdict,
                            "hi",
                            "forced",
                            new BigDecimal("0.01"),
                            5))))
        .toList();
  }
}
