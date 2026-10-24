package com.enrola.anna.web;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Two prompt versions face to face: the same figures for the conversations started on each, and how
 * {@code b} moved from {@code a}. Conversations pin their version, so each column is a clean
 * cohort.
 */
public record CompareView(String a, String b, long startedA, long startedB, List<Row> rows) {

  static final String EVAL_PASS = "evalPass";
  static final String EVAL_PASS_LABEL = "Eval pass^k";

  /** Counts compare per this many conversations started, so cohorts of different size line up. */
  static final int PER = 100;

  public CompareView {
    rows = List.copyOf(rows);
  }

  /** How a figure moved from a to b, read with the figure's direction. */
  public enum Verdict {
    BETTER,
    WORSE,
    SAME,
    NOT_COMPARABLE
  }

  /**
   * One figure side by side. {@code change} is b minus a in the compared unit: the figure's own,
   * except a count, which compares per {@link #PER} conversations started. {@code a} and {@code b}
   * are null in the eval pass^k row until a run exists.
   */
  public record Row(String key, String label, Kpi a, Kpi b, double change, Verdict verdict) {

    /** Either base is under the low n line, so the verdict is a direction, not a result. */
    public boolean lowN() {
      return a == null || b == null || a.lowN() || b.lowN();
    }

    /** A latency either side of which is a second or more, so it reads in seconds. */
    public boolean inSeconds() {
      return a != null && CompareView.inSeconds(a, b);
    }
  }

  /** Either cohort under the low n line. */
  public boolean lowN() {
    return lowNOnA() || lowNOnB();
  }

  public boolean lowNOnA() {
    return startedA < Kpi.LOW_N;
  }

  public boolean lowNOnB() {
    return startedB < Kpi.LOW_N;
  }

  /** No conversation started on either version, so there is nothing to set side by side. */
  public boolean empty() {
    return startedA == 0 && startedB == 0;
  }

  /** {@code a} and {@code b} are the same figure, one per version. */
  static Row row(Kpi a, Kpi b) {
    String label =
        a.unit() == Kpi.Unit.COUNT ? a.label() + " per " + PER + " conversations" : a.label();
    OptionalDouble from = compared(a);
    OptionalDouble to = compared(b);
    if (from.isEmpty() || to.isEmpty()) {
      return new Row(a.key(), label, a, b, 0, Verdict.NOT_COMPARABLE);
    }
    double change = to.getAsDouble() - from.getAsDouble();
    return new Row(a.key(), label, a, b, change, verdict(change, a, b));
  }

  /** The eval pass^k per version, or "no runs" on a side that has none. */
  static Row evalPassRow(Optional<Kpi> a, Optional<Kpi> b) {
    if (a.isPresent() && b.isPresent()) {
      return row(a.get(), b.get());
    }
    return new Row(
        EVAL_PASS, EVAL_PASS_LABEL, a.orElse(null), b.orElse(null), 0, Verdict.NOT_COMPARABLE);
  }

  /** The figure's value, or for a count its rate per {@link #PER} conversations started. */
  static OptionalDouble compared(Kpi kpi) {
    if (kpi.unit() != Kpi.Unit.COUNT) {
      return kpi.value();
    }
    OptionalDouble perConversation = kpi.rate();
    return perConversation.isEmpty()
        ? perConversation
        : OptionalDouble.of(perConversation.getAsDouble() * PER);
  }

  /** A change too small to show at the page's precision is no change. */
  private static Verdict verdict(double change, Kpi a, Kpi b) {
    if (Math.abs(change) < shown(a, b) / 2) {
      return Verdict.SAME;
    }
    return (change > 0) == (a.better() == Kpi.Better.UP) ? Verdict.BETTER : Verdict.WORSE;
  }

  /** The smallest change the page shows for the figure: a tenth of a point, a cent's hundredth. */
  private static double shown(Kpi a, Kpi b) {
    return switch (a.unit()) {
      case RATE -> 0.001;
      case COUNT, TURNS -> 0.1;
      case USD -> 1.0 / Kpi.USD_SCALE;
      case SECONDS -> 1;
      case MILLIS -> inSeconds(a, b) ? 100 : 1;
    };
  }

  private static boolean inSeconds(Kpi a, Kpi b) {
    return a.unit() == Kpi.Unit.MILLIS && Math.max(a.numerator(), b.numerator()) >= 1000;
  }
}
