package com.enrola.anna.eval;

/**
 * How a trial ended. INVALID is a scenario the harness could not run, so nothing was proved about
 * the prompt; the SKIPPED values say why no trial ran: the budget, no key, or a kind the runner
 * does not run.
 */
enum TrialStatus {
  PASS,
  FAIL,
  INVALID,
  SKIPPED_BUDGET,
  SKIPPED_NO_KEY,
  SKIPPED_KIND;

  boolean skipped() {
    return switch (this) {
      case SKIPPED_BUDGET, SKIPPED_NO_KEY, SKIPPED_KIND -> true;
      case PASS, FAIL, INVALID -> false;
    };
  }
}
