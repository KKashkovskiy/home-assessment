package com.enrola.anna.eval;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What makes a run red. Hard: no HARD check failed on any recorded trial, INVALID and skipped ones
 * included, since their replies were really sent. Global: every trial of a scenario tagged {@code
 * core} passed. Validity: INVALID trials are at most the threshold share of the trials that ran. An
 * INVALID trial never counts against global, since it says nothing about Anna, and a FAIL outside
 * the core set and the hard checks is reported, never gating.
 */
final class Gates {

  /** The tag whose scenarios must pass every trial. */
  static final String CORE = "core";

  private Gates() {}

  static GateResult evaluate(
      List<Trial> trials, List<Scenario> selected, double validityThreshold) {
    return new GateResult(
        hardFailures(trials),
        coreFailures(trials, selected),
        (int) trials.stream().filter(trial -> trial.status() == TrialStatus.INVALID).count(),
        (int) trials.stream().filter(trial -> !trial.status().skipped()).count(),
        validityThreshold);
  }

  private static List<GateResult.HardFailure> hardFailures(List<Trial> trials) {
    return trials.stream()
        .flatMap(
            trial ->
                trial.checks().stream()
                    .filter(check -> check.gate() == Check.Gate.HARD && !check.pass())
                    .map(check -> new GateResult.HardFailure(trial, check)))
        .toList();
  }

  private static List<Trial> coreFailures(List<Trial> trials, List<Scenario> selected) {
    Set<String> core =
        selected.stream()
            .filter(scenario -> scenario.tags().contains(CORE))
            .map(Scenario::id)
            .collect(Collectors.toSet());
    return trials.stream()
        .filter(trial -> trial.status() == TrialStatus.FAIL && core.contains(trial.scenarioId()))
        .toList();
  }
}
