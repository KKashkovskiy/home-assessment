package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Every figure's exact fraction over the six seeded conversations, the charts and the compare. */
class AnalyticsServiceTest extends AnalyticsFixture {

  @Test
  void replyRate() {
    assertFraction(view().kpi("reply"), 5, 6);
  }

  @Test
  void bookingOfStartedAndReplied() {
    AnalyticsView view = view();

    assertFraction(view.kpi("bookedOfStarted"), 2, 6);
    assertFraction(view.kpi("bookedOfReplied"), 2, 5);
  }

  @Test
  void optOutSplitKeywordAndModel() {
    AnalyticsView view = view();

    assertFraction(view.kpi("optOut"), 2, 6);
    assertFraction(view.kpi("optOutKeyword"), 1, 6);
    assertFraction(view.kpi("optOutModel"), 1, 6);
  }

  @Test
  void handoffByReasonEmpty() {
    AnalyticsView view = view();

    assertFraction(view.kpi("handoff"), 0, 6);
    assertThat(view.handoffReasons()).hasSize(7).allSatisfy(kpi -> assertFraction(kpi, 0, 6));
    assertThat(view.handoffReasonsSeen()).isEmpty();
  }

  @Test
  void guardrailViolationsCountTheRepairedTurnOnceAndNoRoutingRow() {
    AnalyticsView view = view();

    assertFraction(view.kpi("violations"), 1, 6);
    assertThat(view.kpi("violations").hypothesis()).isEqualTo("target 0");
    assertThat(view.guards())
        .filteredOn(guard -> guard.repair() > 0)
        .extracting(AnalyticsView.GuardRow::name)
        .containsExactlyInAnyOrder("length", "exclamation");
    assertThat(view.guards())
        .filteredOn(guard -> guard.block() + guard.override() > 0)
        .isNotEmpty()
        .allMatch(AnalyticsView.GuardRow::routing);
  }

  @Test
  void turnsToBookMedian() {
    Kpi turns = view().kpi("turnsToBook");

    assertFraction(turns, 50, 2);
    assertThat(turns.unit()).isEqualTo(Kpi.Unit.TURNS);
    assertThat(turns.value()).hasValue(5.0);
  }

  @Test
  void turnsToBookStopAtTheTurnThatBooked() {
    inbound.handle(c1, "STOP", null);

    assertFraction(view().kpi("turnsToBook"), 50, 2);
  }

  @Test
  void timeToBookMedian() {
    Kpi time = view().kpi("timeToBook");

    assertFraction(time, 900, 2);
    assertThat(time.unit()).isEqualTo(Kpi.Unit.SECONDS);
  }

  @Test
  void costPerConversationAndBooking() {
    AnalyticsView view = view();

    assertFraction(view.kpi("costPerConversation"), 1400, 6);
    assertFraction(view.kpi("costPerBooking"), 1400, 2);
    assertThat(view.kpi("costPerBooking").value()).hasValue(0.07);
  }

  @Test
  void latencyPercentilesOverModelTurnsLinkNowhere() {
    AnalyticsView view = view();

    assertFraction(view.kpi("p50"), 2300, 13);
    assertFraction(view.kpi("p95"), 3900, 13);
    assertThat(view.kpi("p50").href()).isNull();
  }

  @Test
  void errorRateOverEveryTurnAndRetryRateOverModelTurns() {
    AnalyticsView view = view();

    assertFraction(view.kpi("errors"), 0, 14);
    assertFraction(view.kpi("retries"), 1, 13);
  }

  @Test
  void opsPerModel() {
    assertThat(view().ops())
        .singleElement()
        .satisfies(
            ops -> {
              assertThat(ops.model()).isEqualTo("gpt-5.1");
              assertThat(ops.calls()).isEqualTo(13);
              assertThat(ops.tokensOut()).isEqualTo(14_000);
              assertThat(ops.cost()).isEqualByComparingTo(new BigDecimal("0.14"));
              assertThat(ops.latencyP50Ms()).isEqualTo(2300);
              assertThat(ops.latencyP95Ms()).isEqualTo(3900);
              assertThat(ops.errors()).isZero();
              assertThat(ops.retries()).isEqualTo(1);
            });
  }

  @Test
  void funnelCountsEveryStageReached() {
    assertThat(view().series("funnel").rows())
        .extracting(Series.Row::href, Series.Row::value)
        .containsExactly(
            tuple("/conversations?reached=ENGAGE&reset=false", 6.0),
            tuple("/conversations?reached=PREFERENCE&reset=false", 3.0),
            tuple("/conversations?reached=CALL_ASK&reset=false", 3.0),
            tuple("/conversations?reached=OFFER_TIMES&reset=false", 2.0),
            tuple("/conversations?reached=BOOKED&reset=false", 2.0));
  }

  @Test
  void statusesPresentInOrder() {
    assertThat(view().series("endReasons").rows())
        .extracting(Series.Row::href, Series.Row::value)
        .containsExactly(
            tuple("/conversations?status=ACTIVE&reset=false", 1.0),
            tuple("/conversations?status=BOOKED&reset=false", 2.0),
            tuple("/conversations?status=DECLINED&reset=false", 1.0),
            tuple("/conversations?status=UNSUBSCRIBED&reset=false", 2.0));
  }

  @Test
  void objectionsWithTheBookedSegment() {
    assertThat(view().series("objections").rows())
        .extracting(Series.Row::href, Series.Row::value, Series.Row::segment)
        .contains(
            tuple("/conversations?objectionType=BUSY&reset=false", 1.0, 1.0),
            tuple("/conversations?objectionType=NOT_INTERESTED&reset=false", 1.0, 0.0),
            tuple("/conversations?objectionType=PRICE&reset=false", 0.0, 0.0));
  }

  @Test
  void costAndLatencyHaveEveryDayOfTheRange() {
    AnalyticsView lastWeek =
        analytics.view(new AnalyticsFilter(AnalyticsFilter.Range.LAST_7D, null, null, null));

    List<Series.Row> cost = lastWeek.series("cost").rows();
    assertThat(cost).hasSize(8);
    assertThat(cost.subList(0, 7)).allMatch(day -> day.value() == null);
    assertThat(cost.getLast().value()).isEqualTo(0.035);
    assertThat(lastWeek.series("latency").rows().getLast().value()).isEqualTo(2.392);
  }

  @Test
  void filterByPromptVersion() {
    AnalyticsView onV2 =
        analytics.view(new AnalyticsFilter(AnalyticsFilter.Range.ALL, null, null, "v2"));

    assertFraction(onV2.kpi("reply"), 1, 1);
  }

  @Test
  void rangeExcludesOld() {
    AnalyticsView lastMonth =
        analytics.view(new AnalyticsFilter(AnalyticsFilter.Range.LAST_30D, null, null, null));

    assertThat(lastMonth.started()).isEqualTo(5);
    assertThat(lastMonth.since()).isEqualTo(Instant.parse("2026-06-26T00:00:00Z"));
    assertFraction(lastMonth.kpi("costPerConversation"), 1400, 5);
  }

  @Test
  void resetConversationIsNotAStart() {
    conversations.reset(c6);

    AnalyticsView view = view();

    assertThat(view.started()).isEqualTo(6);
    assertFraction(view.kpi("reply"), 5, 6);
  }

  @Test
  void everyTileIsLowN() {
    assertThat(view().kpis()).allMatch(Kpi::lowN);
  }

  @Test
  void emptyDatabaseHasNoRatesAndNoCharts() {
    jdbc.update("delete from booking");
    jdbc.update("delete from trace_event");
    jdbc.update("delete from turn");
    jdbc.update("delete from message");
    jdbc.update("delete from conversation");

    AnalyticsView empty = view();

    assertThat(empty.kpis())
        .filteredOn(kpi -> kpi.unit() != Kpi.Unit.COUNT)
        .allSatisfy(kpi -> assertThat(kpi.value()).isEmpty());
    assertThat(empty.charts()).allSatisfy(series -> assertThat(series.rows()).isEmpty());
    assertThat(empty.ops()).isEmpty();
    assertThat(empty.violationsAtTarget()).isFalse();
  }

  @Nested
  class Compare {

    @Test
    void deltaAndDirection() {
      CompareView compare = analytics.compare("v1", "v2", allTime());

      assertThat(compare.startedA()).isEqualTo(5);
      assertThat(compare.startedB()).isEqualTo(1);
      assertThat(compare.lowN()).isTrue();
      assertThat(row(compare, "reply").change()).isCloseTo(0.2, within(1e-9));
      assertThat(row(compare, "reply").verdict()).isEqualTo(CompareView.Verdict.BETTER);
      assertThat(row(compare, "bookedOfStarted").verdict()).isEqualTo(CompareView.Verdict.WORSE);
      assertThat(row(compare, "turnsToBook").verdict())
          .isEqualTo(CompareView.Verdict.NOT_COMPARABLE);
    }

    @Test
    void violationsComparePerHundredConversations() {
      CompareView.Row violations = row(analytics.compare("v1", "v2", allTime()), "violations");

      assertThat(violations.change()).isCloseTo(-20.0, within(1e-9));
      assertThat(violations.verdict()).isEqualTo(CompareView.Verdict.BETTER);
    }

    @Test
    void comparesTheTilesButNotTheHandoffReasons() {
      List<String> keys =
          analytics.compare("v1", "v2", allTime()).rows().stream()
              .map(CompareView.Row::key)
              .toList();

      assertThat(keys)
          .containsExactlyElementsOf(
              Stream.concat(view().kpis().stream().map(Kpi::key), Stream.of(CompareView.EVAL_PASS))
                  .toList());
    }

    @Test
    void evalPassRateNoRuns() {
      CompareView.Row evalPass = row(analytics.compare("v1", "v2", allTime()), "evalPass");

      assertThat(evalPass.a()).isNull();
      assertThat(evalPass.b()).isNull();
      assertThat(evalPass.verdict()).isEqualTo(CompareView.Verdict.NOT_COMPARABLE);
    }

    @Test
    void sameVersionHasNoChange() {
      List<CompareView.Row> rows = analytics.compare("v1", "v1", allTime()).rows();

      assertThat(rows)
          .filteredOn(row -> row.a() != null && row.a().value().isPresent())
          .isNotEmpty()
          .allSatisfy(row -> assertThat(row.verdict()).isEqualTo(CompareView.Verdict.SAME));
    }
  }

  private AnalyticsView view() {
    return analytics.view(allTime());
  }

  private static CompareView.Row row(CompareView compare, String key) {
    return compare.rows().stream().filter(row -> row.key().equals(key)).findFirst().orElseThrow();
  }

  private static void assertFraction(Kpi kpi, long numerator, long denominator) {
    assertThat(kpi.numerator()).as(kpi.key() + " numerator").isEqualTo(numerator);
    assertThat(kpi.denominator()).as(kpi.key() + " denominator").isEqualTo(denominator);
  }
}
