package com.enrola.anna.eval;

import java.util.List;

/**
 * A run's three gates with what decided each: the hard checks that failed, the core trials that
 * failed, and the INVALID trials among those that ran against the threshold. Exactly three gates:
 * judges stay advisory and never gate.
 */
record GateResult(
    List<HardFailure> hardFailures,
    List<Trial> coreFailures,
    int invalid,
    int ran,
    double validityThreshold) {

  /** A HARD check that failed on a recorded trial. */
  record HardFailure(Trial trial, Check check) {}

  GateResult {
    hardFailures = List.copyOf(hardFailures);
    coreFailures = List.copyOf(coreFailures);
  }

  boolean hard() {
    return hardFailures.isEmpty();
  }

  boolean global() {
    return coreFailures.isEmpty();
  }

  boolean validity() {
    return invalidShare() <= validityThreshold;
  }

  /** INVALID over the trials that ran; 0 when none ran. */
  double invalidShare() {
    return ran == 0 ? 0 : (double) invalid / ran;
  }

  /** 0 when green; 1 hard failed, 2 global failed, 4 validity failed, summed. */
  int exitCode() {
    return (hard() ? 0 : 1) | (global() ? 0 : 2) | (validity() ? 0 : 4);
  }
}
