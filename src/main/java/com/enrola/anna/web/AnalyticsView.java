package com.enrola.anna.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything the analytics page shows for one filter: the earliest start counted (null for all
 * time), how many conversations started since, the tiles, the handoff rate by reason, the charts,
 * each guard's verdicts and each model's operations.
 */
public record AnalyticsView(
    Instant since,
    long started,
    List<Kpi> kpis,
    List<Kpi> handoffReasons,
    List<Series> charts,
    List<GuardRow> guards,
    List<OpsRow> ops) {

  public AnalyticsView {
    kpis = List.copyOf(kpis);
    handoffReasons = List.copyOf(handoffReasons);
    charts = List.copyOf(charts);
    guards = List.copyOf(guards);
    ops = List.copyOf(ops);
  }

  /**
   * One guard's verdicts in range; INFO rows are records, not verdicts, and are left out. A {@code
   * routing} guard records code choosing a route, so its blocks are not violations.
   */
  public record GuardRow(
      String name, long pass, long block, long repair, long override, boolean routing) {}

  /** One model's turns that called it; latency percentiles are nearest rank over those turns. */
  public record OpsRow(
      String model,
      long calls,
      long tokensIn,
      long tokensCached,
      long tokensOut,
      long tokensReasoning,
      BigDecimal cost,
      long latencyP50Ms,
      long latencyP95Ms,
      long errors,
      long retries) {}

  /** The figure with this key; an unknown key is a programming error. */
  public Kpi kpi(String key) {
    return kpis.stream()
        .filter(kpi -> kpi.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no KPI " + key));
  }

  /** The handoff reasons that happened, for the handoff tile's breakdown. */
  public List<Kpi> handoffReasonsSeen() {
    return handoffReasons.stream().filter(kpi -> kpi.numerator() > 0).toList();
  }

  /** Conversations started and no turn had a guard catch the model. */
  public boolean violationsAtTarget() {
    Kpi violations = kpi("violations");
    return violations.denominator() > 0 && violations.numerator() == 0;
  }

  public boolean violationsAboveTarget() {
    return kpi("violations").numerator() > 0;
  }

  /** The chart with this {@code data-chart} key; an unknown key is a programming error. */
  public Series series(String key) {
    return charts.stream()
        .filter(series -> series.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no chart " + key));
  }
}
