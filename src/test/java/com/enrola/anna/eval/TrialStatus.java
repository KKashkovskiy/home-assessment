package com.enrola.anna.eval;

/**
 * How a trial ended. INVALID is a story whose simulated lead broke its persona or whose call
 * failed: it proves nothing about Anna, so it counts toward the validity gate alone. The SKIPPED
 * values say why a trial did not run, or not to its end: the budget, no key, or a story the offline
 * actor has no lines for.
 */
enum TrialStatus {
  PASS,
  FAIL,
  INVALID,
  SKIPPED_BUDGET,
  SKIPPED_NO_KEY,
  SKIPPED_KIND;

  /** PASS or FAIL: a verdict about Anna, which the statistics and the gates count. */
  boolean judged() {
    return this == PASS || this == FAIL;
  }

  boolean skipped() {
    return switch (this) {
      case SKIPPED_BUDGET, SKIPPED_NO_KEY, SKIPPED_KIND -> true;
      case PASS, FAIL, INVALID -> false;
    };
  }
}
