package com.enrola.anna.web;

/**
 * How an eval run ended, read from what it left behind, never from Maven's exit, which is only 0 or
 * 1. A failed gate's bit in its {@code exit-code} file makes it red, else its nothing-judged bit
 * makes it skipped, whatever else is there. Otherwise its verdict decides, from its {@code
 * eval_run} row or, without one, its report's header. A report with neither a verdict nor an exit
 * code is a dry run's plan. Anything else, exit code 0 alone included, is an error the log
 * explains. A cancelled run is cancelled whatever it wrote.
 */
public enum RunOutcome {
  GREEN,
  RED,
  /**
   * Nothing was judged: no eval key, the estimate over the budget, or every trial skipped or
   * INVALID.
   */
  SKIPPED,
  /** A dry run: the plan and its estimate, no call. */
  PLANNED,
  CANCELLED,
  /** No verdict in its row or report: the run crashed before its report. */
  ERROR;

  /** The exit code's bit for a run that judged no trial, beside the gates' 1, 2 and 4. */
  static final int NOTHING_JUDGED = 8;

  /**
   * What a finished run left: its verdict from the row, else from the report (null with neither),
   * its exit code from the {@code exit-code} file (null without one), and whether it wrote a
   * report.
   */
  record Evidence(String verdict, Integer exitCode, boolean reported) {}

  static RunOutcome of(Evidence evidence) {
    Integer code = evidence.exitCode();
    if (code != null && (code & ~NOTHING_JUDGED) != 0) {
      return RED;
    }
    if (code != null && (code & NOTHING_JUDGED) != 0) {
      return SKIPPED;
    }
    if (evidence.verdict() != null) {
      return switch (evidence.verdict()) {
        case "PASS" -> GREEN;
        case "FAIL" -> RED;
        case "SKIPPED" -> SKIPPED;
        default -> ERROR;
      };
    }
    return evidence.reported() && code == null ? PLANNED : ERROR;
  }
}
