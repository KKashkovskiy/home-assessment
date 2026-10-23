package com.enrola.anna.eval;

import java.math.BigDecimal;
import java.util.List;

/**
 * A run's trials against what it selected: counts by status, cost, and the verdict the report and
 * the row show. A selected scenario with no trial crashed, so the run fails.
 */
record RunSummary(List<Trial> trials, int selected) {

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

  /** Selected scenarios with no trial: each threw before it could be recorded. */
  int crashed() {
    return selected - trials.size();
  }

  BigDecimal cost() {
    return trials.stream().map(Trial::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** FAIL on a crash, a FAIL or an INVALID trial; else SKIPPED when nothing ran; else PASS. */
  Verdict verdict() {
    if (crashed() > 0 || count(TrialStatus.FAIL) + count(TrialStatus.INVALID) > 0) {
      return Verdict.FAIL;
    }
    return ran() == 0 ? Verdict.SKIPPED : Verdict.PASS;
  }
}
