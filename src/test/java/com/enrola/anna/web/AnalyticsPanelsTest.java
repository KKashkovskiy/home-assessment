package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.enrola.anna.conversation.TraceOutcome;
import com.enrola.anna.conversation.TurnStatus;
import com.enrola.anna.web.AnalyticsQueries.GuardCount;
import com.enrola.anna.web.AnalyticsQueries.TurnRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The guard and ops tables from their rows: verdicts only, and each model's errors and retries. */
class AnalyticsPanelsTest {

  @Test
  void guardsKeepVerdictsAndMarkRoutingAndDropRecordsOnly() {
    List<AnalyticsView.GuardRow> guards =
        AnalyticsPanels.guards(
            List.of(
                new GuardCount("moderation", TraceOutcome.INFO, 4),
                new GuardCount("length", TraceOutcome.PASS, 9),
                new GuardCount("length", TraceOutcome.REPAIR, 1),
                new GuardCount("pre-guards", TraceOutcome.BLOCK, 2),
                new GuardCount("pre-guards", TraceOutcome.INFO, 3)));

    assertThat(guards)
        .extracting(
            AnalyticsView.GuardRow::name,
            AnalyticsView.GuardRow::pass,
            AnalyticsView.GuardRow::block,
            AnalyticsView.GuardRow::repair,
            AnalyticsView.GuardRow::routing)
        .containsExactly(tuple("length", 9L, 0L, 1L, false), tuple("pre-guards", 0L, 2L, 0L, true));
  }

  @Test
  void opsCountErrorsAndRetriesOverModelTurnsOnly() {
    List<AnalyticsView.OpsRow> ops =
        AnalyticsPanels.ops(
            List.of(
                turn("gpt-5.1", TurnStatus.OK, 2000),
                turn("gpt-5.1", TurnStatus.ERROR, 3000),
                turn("gpt-5.1", TurnStatus.FALLBACK, 1000),
                turn(null, TurnStatus.OK, 0)));

    assertThat(ops)
        .singleElement()
        .extracting(
            AnalyticsView.OpsRow::calls,
            AnalyticsView.OpsRow::errors,
            AnalyticsView.OpsRow::retries,
            AnalyticsView.OpsRow::latencyP50Ms)
        .containsExactly(3L, 1L, 1L, 2000L);
  }

  private static TurnRow turn(String model, TurnStatus status, int latencyMs) {
    return new TurnRow("c-1", model, status, BigDecimal.ZERO, latencyMs, 0, 0, 0, 0, Instant.EPOCH);
  }
}
