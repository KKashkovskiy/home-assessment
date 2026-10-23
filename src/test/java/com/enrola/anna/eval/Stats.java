package com.enrola.anna.eval;

import java.util.Collection;
import java.util.List;

/**
 * The two numbers a run of k trials per scenario reports, neither averaged into the other: pass^k,
 * the share of scenarios whose every judged trial passed, and the Wilson score interval on the
 * per-trial pass rate. Only judged trials count (PASS or FAIL): INVALID and skipped ones say
 * nothing about Anna.
 */
final class Stats {

  /** z for a 95% interval. */
  static final double Z95 = 1.96;

  /** A rate's bounds; both NaN when nothing was judged. */
  record Interval(double low, double high) {}

  private Stats() {}

  /**
   * The share of scenarios with a judged trial that are {@link #clean}; NaN when none has one.
   * {@code byScenario} holds each scenario's trials.
   */
  static double passK(Collection<List<Trial>> byScenario) {
    List<List<Trial>> judged = byScenario.stream().filter(Stats::anyJudged).toList();
    long clean = judged.stream().filter(Stats::clean).count();
    return judged.isEmpty() ? Double.NaN : (double) clean / judged.size();
  }

  /** A scenario's trials with at least one judged and none FAIL. */
  static boolean clean(List<Trial> trials) {
    return anyJudged(trials)
        && trials.stream().noneMatch(trial -> trial.status() == TrialStatus.FAIL);
  }

  /**
   * The Wilson score interval for {@code passes} of {@code n} at {@code z}. Unlike {@code p ± z·se}
   * it stays inside 0 to 1 and is honest near both ends and at small n.
   */
  static Interval wilson(int passes, int n, double z) {
    if (n == 0) {
      return new Interval(Double.NaN, Double.NaN);
    }
    double p = (double) passes / n;
    double z2 = z * z;
    double centre = (p + z2 / (2.0 * n)) / (1 + z2 / n);
    double half = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n);
    return new Interval(Math.max(0, centre - half), Math.min(1, centre + half));
  }

  /** A scenario's trials with at least one judged. */
  static boolean anyJudged(List<Trial> trials) {
    return trials.stream().anyMatch(trial -> trial.status().judged());
  }
}
