package com.enrola.anna.web;

import com.enrola.anna.web.AnalyticsQueries.Cohort;
import com.enrola.anna.web.AnalyticsQueries.GuardCount;
import com.enrola.anna.web.AnalyticsQueries.ObjectionCount;
import com.enrola.anna.web.AnalyticsQueries.Started;
import com.enrola.anna.web.AnalyticsQueries.TurnRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Everything one analytics filter reads, read once: when it was read and the earliest start counted
 * (null for all time), the cohort as SQL, its conversations and their turns, the turns on which a
 * guard caught the model, each guard's verdict counts, the objections raised, and the list filter
 * every drill-down link starts from. The tiles, charts and tables are computed from these alone.
 */
record AnalyticsRows(
    Instant now,
    Instant since,
    Cohort cohort,
    ConversationFilter links,
    List<Started> started,
    List<TurnRow> turns,
    long violations,
    List<GuardCount> guardCounts,
    List<ObjectionCount> objections) {

  AnalyticsRows {
    started = List.copyOf(started);
    turns = List.copyOf(turns);
    guardCounts = List.copyOf(guardCounts);
    objections = List.copyOf(objections);
  }

  long count(Predicate<Started> which) {
    return started.stream().filter(which).count();
  }

  List<Started> booked() {
    return started.stream().filter(Started::goalMet).toList();
  }

  List<TurnRow> modelTurns() {
    return turns.stream().filter(TurnRow::calledModel).toList();
  }

  BigDecimal cost() {
    return turns.stream().map(TurnRow::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** The conversation list of this cohort. */
  String listUrl() {
    return links.url(ConversationsController.LIST);
  }

  /** The conversation list of this cohort narrowed by one more field. */
  String listUrl(ConversationFilter.Param param, Object value) {
    return links.url(ConversationsController.LIST, param, value);
  }

  /** The conversation list of this cohort narrowed by more fields. */
  String listUrl(Map<ConversationFilter.Param, ?> narrowing) {
    return links.url(ConversationsController.LIST, narrowing);
  }
}
