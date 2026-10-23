package com.enrola.anna.web;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.lead.Suppression;
import com.enrola.anna.web.AnalyticsQueries.Started;
import com.enrola.anna.web.AnalyticsQueries.TurnRow;
import com.enrola.anna.web.ConversationFilter.Param;
import com.enrola.anna.web.Kpi.Better;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * What each figure counts: its numerator and the base it is out of, the list that shows the
 * conversations it counted, its target line and which way is better. A new figure is one more
 * {@link Kpi} here and its tile in {@code fragments/kpis.html}, which places the tiles by key.
 */
final class AnalyticsKpis {

  private AnalyticsKpis() {}

  /** Every figure for {@code rows}: engagement, then safety, then cost and speed. */
  static List<Kpi> of(AnalyticsRows rows) {
    List<Kpi> kpis = new ArrayList<>(engagement(rows));
    kpis.addAll(safety(rows));
    kpis.addAll(cost(rows));
    return kpis;
  }

  /** The handoff rate split by reason, one figure for each reason a handoff can have. */
  static List<Kpi> handoffReasons(AnalyticsRows rows) {
    return Arrays.stream(HandoffReason.values())
        .filter(reason -> reason != HandoffReason.NONE)
        .map(
            reason ->
                rate(
                    reason.name(),
                    reason.name().replace('_', ' '),
                    rows.count(c -> c.needsHuman() && c.handoffReason() == reason),
                    rows.started().size(),
                    rows.listUrl(Map.of(Param.NEEDS_HUMAN, true, Param.HANDOFF_REASON, reason))))
        .toList();
  }

  /** Reply, booking of started and of replied, opt-out and its keyword and model split. */
  private static List<Kpi> engagement(AnalyticsRows rows) {
    long started = rows.started().size();
    long replied = rows.count(Started::replied);
    long booked = rows.count(Started::goalMet);
    String bookedList = rows.listUrl(Param.STAGE, Stage.BOOKED);
    String optedOutList = rows.listUrl(Param.END_REASON, EndReason.UNSUBSCRIBED);
    long optedOut = rows.count(Started::unsubscribed);
    return List.of(
        rate("reply", "Reply rate", replied, started, rows.listUrl(Param.REPLIED, true))
            .withHypothesis("10 to 25%")
            .withBetter(Better.UP),
        rate("bookedOfStarted", "Booking rate, of started", booked, started, bookedList)
            .withHypothesis("set after baseline")
            .withBetter(Better.UP),
        rate("bookedOfReplied", "Booking rate, of replied", booked, replied, bookedList)
            .withBetter(Better.UP),
        rate("optOut", "Opt-out rate", optedOut, started, optedOutList).withHypothesis("1 to 1.5%"),
        optOutBy(rows, Suppression.Reason.KEYWORD, "optOutKeyword", "Opt-out, STOP keyword"),
        optOutBy(rows, Suppression.Reason.MODEL, "optOutModel", "Opt-out, model intent"));
  }

  private static Kpi optOutBy(
      AnalyticsRows rows, Suppression.Reason reason, String key, String label) {
    return rate(
        key,
        label,
        rows.count(c -> c.unsubscribed() && c.optOutReason() == reason),
        rows.started().size(),
        rows.listUrl(
            Map.of(Param.END_REASON, EndReason.UNSUBSCRIBED, Param.OPT_OUT_REASON, reason)));
  }

  /** Handoffs, guardrail violations, and the error and retry rates, which count turns. */
  private static List<Kpi> safety(AnalyticsRows rows) {
    long started = rows.started().size();
    List<TurnRow> modelTurns = rows.modelTurns();
    return List.of(
        rate(
            "handoff",
            "Handoff rate",
            rows.count(Started::needsHuman),
            started,
            rows.listUrl(Param.NEEDS_HUMAN, true)),
        new Kpi(
            "violations",
            "Guardrail violations",
            rows.violations(),
            started,
            Kpi.Unit.COUNT,
            rows.listUrl(Param.GUARD_CAUGHT, true),
            "target 0",
            Better.DOWN),
        rate(
            "errors",
            "Error rate, of turns",
            count(rows.turns(), TurnRow::failed),
            rows.turns().size(),
            rows.listUrl(Param.ERRORED, true)),
        rate(
            "retries",
            "Retry rate, of model turns",
            count(modelTurns, TurnRow::retried),
            modelTurns.size(),
            rows.listUrl(Param.RETRIED, true)));
  }

  /** Turns and time to book, cost per conversation and per booking, latency p50 and p95. */
  private static List<Kpi> cost(AnalyticsRows rows) {
    List<Started> booked = rows.booked();
    String bookedList = rows.listUrl(Param.STAGE, Stage.BOOKED);
    long cost = usdUnits(rows.cost());
    return List.of(
        turnsToBook(booked, bookedList),
        timeToBook(booked, bookedList),
        new Kpi(
            "costPerConversation",
            "Cost per conversation",
            cost,
            rows.started().size(),
            Kpi.Unit.USD,
            rows.listUrl(),
            "under $0.50",
            Better.DOWN),
        new Kpi(
            "costPerBooking",
            "Cost per booking",
            cost,
            booked.size(),
            Kpi.Unit.USD,
            bookedList,
            null,
            Better.DOWN),
        latency(50, rows.modelTurns()),
        latency(95, rows.modelTurns()));
  }

  /** The agent's replies up to and including the turn that booked. */
  private static Kpi turnsToBook(List<Started> booked, String bookedList) {
    List<Long> replies =
        booked.stream()
            .map(Started::repliesToBook)
            .filter(Objects::nonNull)
            .map(Integer::longValue)
            .toList();
    long tenths = Math.round(Percentiles.median(replies).orElse(0) * Kpi.TENTHS);
    return new Kpi(
        "turnsToBook",
        "Median turns to book",
        tenths,
        replies.size(),
        Kpi.Unit.TURNS,
        bookedList,
        null,
        Better.DOWN);
  }

  /** From the opener to the booking row. */
  private static Kpi timeToBook(List<Started> booked, String bookedList) {
    List<Long> seconds =
        booked.stream()
            .filter(c -> c.bookedAt() != null)
            .map(c -> Duration.between(c.createdAt(), c.bookedAt()).toSeconds())
            .toList();
    long median = Math.round(Percentiles.median(seconds).orElse(0));
    return new Kpi(
        "timeToBook",
        "Median time to book",
        median,
        seconds.size(),
        Kpi.Unit.SECONDS,
        bookedList,
        null,
        Better.DOWN);
  }

  /** No list holds exactly the model turns, so a latency figure links nowhere. */
  private static Kpi latency(int percentile, List<TurnRow> modelTurns) {
    List<Long> latencies = modelTurns.stream().map(turn -> (long) turn.latencyMs()).toList();
    return new Kpi(
        "p" + percentile,
        "Turn latency p" + percentile,
        Percentiles.of(latencies, percentile).orElse(0),
        latencies.size(),
        Kpi.Unit.MILLIS,
        null,
        null,
        Better.DOWN);
  }

  /** A rate where down is better and no target is named. */
  private static Kpi rate(String key, String label, long numerator, long base, String href) {
    return new Kpi(key, label, numerator, base, Kpi.Unit.RATE, href, null, Better.DOWN);
  }

  private static long count(List<TurnRow> turns, Predicate<TurnRow> which) {
    return turns.stream().filter(which).count();
  }

  /** Dollars as the integer a {@link Kpi} carries. */
  private static long usdUnits(BigDecimal usd) {
    return usd.multiply(BigDecimal.valueOf(Kpi.USD_SCALE))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
  }
}
