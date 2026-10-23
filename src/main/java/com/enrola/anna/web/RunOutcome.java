package com.enrola.anna.web;

/**
 * How an eval run ended, read from what it left behind, never from Maven's exit, which is only 0 or
 * 1: a failed gate in its {@code exit-code} file makes it red whatever else is there; otherwise its
 * {@code eval_run} row's verdict decides; without a row, a report with green gates is green and a
 * report without gates a dry run's plan; anything less is an error the log explains. A cancelled
 * run is cancelled whatever it wrote.
 */
public enum RunOutcome {
  GREEN,
  RED,
  /** Nothing ran: no eval key, or the estimate was over the budget. */
  SKIPPED,
  /** A dry run: the plan and its estimate, no call. */
  PLANNED,
  CANCELLED,
  /** No verdict: the run crashed, or its row could not be written. */
  ERROR;

  /**
   * What a finished run left: its row's verdict (null without a row), its gates' code from the
   * {@code exit-code} file (null without one), and whether it wrote a report.
   */
  record Evidence(String verdict, Integer gatesCode, boolean reported) {}

  static RunOutcome of(Evidence evidence) {
    if (evidence.gatesCode() != null && evidence.gatesCode() != 0) {
      return RED;
    }
    if (evidence.verdict() != null) {
      return switch (evidence.verdict()) {
        case "PASS" -> GREEN;
        case "FAIL" -> RED;
        case "SKIPPED" -> SKIPPED;
        default -> ERROR;
      };
    }
    if (!evidence.reported()) {
      return ERROR;
    }
    return evidence.gatesCode() == null ? PLANNED : GREEN;
  }
}
