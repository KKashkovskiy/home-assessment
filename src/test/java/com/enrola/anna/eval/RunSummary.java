package com.enrola.anna.eval;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A run's trials against the {@code expected} count, every selected scenario times the trials per
 * scenario, with the gates they passed: counts by status, cost, the statistics and the verdict the
 * report and the row show. An expected trial with no record crashed, so the run fails.
 */
record RunSummary(List<Trial> trials, int expected, GateResult gates) {

  /** How the run went. */
  enum Verdict {
    PASS,
    FAIL,
    SKIPPED
  }

  RunSummary {
    trials = List.copyOf(trials);
  }

  int count(TrialStatus status) {
    return (int) trials.stream().filter(trial -> trial.status() == status).count();
  }

  int skipped() {
    return (int) trials.stream().filter(trial -> trial.status().skipped()).count();
  }

  int ran() {
    return trials.size() - skipped();
  }

  /** PASS and FAIL trials: the per-trial pass rate's n. */
  int judged() {
    return count(TrialStatus.PASS) + count(TrialStatus.FAIL);
  }

  /** Expected trials with no record: each threw before it could be recorded. */
  int crashed() {
    return expected - trials.size();
  }

  BigDecimal cost() {
    return trials.stream().map(Trial::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** Each scenario's trials in run order, scenarios in the order they first ran. */
  List<List<Trial>> byScenario() {
    return List.copyOf(
        trials.stream()
            .collect(
                Collectors.groupingBy(Trial::scenarioId, LinkedHashMap::new, Collectors.toList()))
            .values());
  }

  /** Scenarios with a judged trial, the denominator of pass^k. */
  int judgedScenarios() {
    return (int) byScenario().stream().filter(Stats::anyJudged).count();
  }

  /** Scenarios with a judged trial and no FAIL. */
  int cleanScenarios() {
    return (int) byScenario().stream().filter(Stats::clean).count();
  }

  /** The share of scenarios with every judged trial passed; NaN when nothing was judged. */
  double passK() {
    return Stats.passK(byScenario());
  }

  /** The per-trial pass rate's 95% Wilson interval. */
  Stats.Interval passRateInterval() {
    return Stats.wilson(count(TrialStatus.PASS), judged(), Stats.Z95);
  }

  /** FAIL on a crash or a red gate; else SKIPPED when nothing ran; else PASS. */
  Verdict verdict() {
    if (crashed() > 0 || gates.exitCode() != 0) {
      return Verdict.FAIL;
    }
    return ran() == 0 ? Verdict.SKIPPED : Verdict.PASS;
  }
}
