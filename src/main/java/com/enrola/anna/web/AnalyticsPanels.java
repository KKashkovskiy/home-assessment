package com.enrola.anna.web;

import com.enrola.anna.agent.ObjectionType;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.ConversationStatus;
import com.enrola.anna.conversation.RoutingGuards;
import com.enrola.anna.conversation.TraceOutcome;
import com.enrola.anna.web.AnalyticsQueries.GuardCount;
import com.enrola.anna.web.AnalyticsQueries.ObjectionCount;
import com.enrola.anna.web.AnalyticsQueries.Started;
import com.enrola.anna.web.AnalyticsQueries.TurnRow;
import com.enrola.anna.web.ConversationFilter.Param;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The page's panels below the tiles, from one filter's rows: five charts as {@link Series} (how far
 * conversations got, how they stand, the objections raised and how many still booked, and cost and
 * latency by day in the advisors' zone), each guard's verdicts, and each model's operations. Each
 * chart row carries its value as printed and the link to the conversations behind it, so a chart
 * and its table view say the same thing.
 */
@Component
class AnalyticsPanels {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
  private static final String NOTHING = "–";

  private final ViewFormats fmt;

  AnalyticsPanels(ViewFormats fmt) {
    this.fmt = fmt;
  }

  /** The five charts, days in {@code advisorZone}; a chart with nothing in range has no rows. */
  List<Series> charts(AnalyticsRows rows, ZoneId advisorZone) {
    SortedMap<LocalDate, List<TurnRow>> byDay = modelTurnsByDay(rows, advisorZone);
    return List.of(
        funnel(rows), statuses(rows), objections(rows), costByDay(byDay), latencyByDay(byDay));
  }

  /** Each guard's verdict counts, by name; a guard with only INFO rows gave no verdict. */
  static List<AnalyticsView.GuardRow> guards(List<GuardCount> counts) {
    Map<String, Map<TraceOutcome, Long>> byName = new TreeMap<>();
    for (GuardCount count : counts) {
      byName
          .computeIfAbsent(count.name(), name -> new EnumMap<>(TraceOutcome.class))
          .merge(count.outcome(), count.count(), Long::sum);
    }
    return byName.entrySet().stream()
        .map(guard -> guardRow(guard.getKey(), guard.getValue()))
        .filter(row -> row.pass() + row.block() + row.repair() + row.override() > 0)
        .toList();
  }

  /** One row per model, over the turns that called it. */
  static List<AnalyticsView.OpsRow> ops(List<TurnRow> turns) {
    Map<String, List<TurnRow>> byModel =
        turns.stream()
            .filter(TurnRow::calledModel)
            .collect(Collectors.groupingBy(TurnRow::model, TreeMap::new, Collectors.toList()));
    return byModel.entrySet().stream()
        .map(model -> opsRow(model.getKey(), model.getValue()))
        .toList();
  }

  /**
   * Conversations that reached each stage: a conversation at OFFER_TIMES passed CALL_ASK, so it
   * counts for both.
   */
  private Series funnel(AnalyticsRows rows) {
    List<Started> started = rows.started();
    if (started.isEmpty()) {
      return new Series("funnel", List.of());
    }
    Map<Stage, Long> at = counted(started, Started::stage, Stage.class);
    List<Series.Row> funnel = new ArrayList<>();
    long previous = started.size();
    for (Stage stage : Stage.values()) {
      long reached =
          EnumSet.range(stage, Stage.BOOKED).stream().mapToLong(s -> at.getOrDefault(s, 0L)).sum();
      String drop = stage == Stage.ENGAGE ? "" : ", drop " + (previous - reached);
      funnel.add(
          countRow(
              fmt.stage(stage),
              reached,
              share(reached, started.size()) + drop,
              rows.listUrl(Param.REACHED, stage)));
      previous = reached;
    }
    return new Series("funnel", funnel);
  }

  /** How conversations stand, one row per state present: open ones, then each ending. */
  private Series statuses(AnalyticsRows rows) {
    List<Started> started = rows.started();
    List<Series.Row> statuses = new ArrayList<>();
    counted(started, Started::status, ConversationStatus.class)
        .forEach(
            (status, count) ->
                statuses.add(
                    countRow(
                        fmt.words(status),
                        count,
                        share(count, started.size()),
                        rows.listUrl(Param.STATUS, status))));
    return new Series("endReasons", statuses);
  }

  /**
   * Each objection type with the conversations that raised it; the segment is those that booked.
   */
  private Series objections(AnalyticsRows rows) {
    if (rows.objections().isEmpty()) {
      return new Series("objections", List.of());
    }
    Map<String, ObjectionCount> byType =
        rows.objections().stream()
            .collect(Collectors.toMap(ObjectionCount::type, Function.identity()));
    List<Series.Row> objections = new ArrayList<>();
    for (ObjectionType type : EnumSet.complementOf(EnumSet.of(ObjectionType.NONE))) {
      ObjectionCount count =
          byType.getOrDefault(type.name(), new ObjectionCount(type.name(), 0, 0));
      objections.add(
          new Series.Row(
              fmt.words(type),
              (double) count.conversations(),
              (double) count.recovered(),
              Long.toString(count.conversations()),
              count.recovered() + " booked anyway",
              rows.listUrl(Param.OBJECTION_TYPE, type)));
    }
    return new Series("objections", objections);
  }

  /** The day's spend over the conversations it touched; a day without a model call has none. */
  private Series costByDay(SortedMap<LocalDate, List<TurnRow>> byDay) {
    List<Series.Row> days = new ArrayList<>();
    byDay.forEach(
        (day, turns) -> {
          if (turns.isEmpty()) {
            days.add(emptyDay(day));
            return;
          }
          BigDecimal spend =
              turns.stream().map(TurnRow::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
          long conversations = turns.stream().map(TurnRow::conversationId).distinct().count();
          BigDecimal perConversation =
              spend.divide(BigDecimal.valueOf(conversations), 4, RoundingMode.HALF_UP);
          days.add(
              new Series.Row(
                  DAY.format(day),
                  perConversation.doubleValue(),
                  null,
                  fmt.cost(perConversation),
                  fmt.cost(spend) + " over " + fmt.count(conversations, "conversation"),
                  null));
        });
    return new Series("cost", days);
  }

  /** The day's mean turn latency, in seconds; the tiles carry the percentiles. */
  private Series latencyByDay(SortedMap<LocalDate, List<TurnRow>> byDay) {
    List<Series.Row> days = new ArrayList<>();
    byDay.forEach(
        (day, turns) -> {
          if (turns.isEmpty()) {
            days.add(emptyDay(day));
            return;
          }
          long meanMs = Math.round(turns.stream().mapToInt(TurnRow::latencyMs).average().orElse(0));
          days.add(
              new Series.Row(
                  DAY.format(day),
                  meanMs / 1000.0,
                  null,
                  fmt.latency(meanMs),
                  fmt.count(turns.size(), "model turn"),
                  null));
        });
    return new Series("latency", days);
  }

  /**
   * Every advisor-zone day from the range's first, or the first model call's for all time, to
   * today, each with its model turns, so a quiet day is a gap rather than a skipped point. Empty
   * when no turn in range called the model.
   */
  private static SortedMap<LocalDate, List<TurnRow>> modelTurnsByDay(
      AnalyticsRows rows, ZoneId zone) {
    SortedMap<LocalDate, List<TurnRow>> byDay =
        rows.modelTurns().stream()
            .collect(
                Collectors.groupingBy(
                    turn -> turn.createdAt().atZone(zone).toLocalDate(),
                    TreeMap::new,
                    Collectors.toList()));
    if (byDay.isEmpty()) {
      return byDay;
    }
    LocalDate first =
        rows.since() == null ? byDay.firstKey() : rows.since().atZone(zone).toLocalDate();
    LocalDate today = rows.now().atZone(zone).toLocalDate();
    first.datesUntil(today.plusDays(1)).forEach(day -> byDay.putIfAbsent(day, List.of()));
    return byDay;
  }

  private static Series.Row countRow(String label, long count, String note, String href) {
    return new Series.Row(label, (double) count, null, Long.toString(count), note, href);
  }

  private static Series.Row emptyDay(LocalDate day) {
    return new Series.Row(DAY.format(day), null, null, NOTHING, "no model calls", null);
  }

  private static AnalyticsView.GuardRow guardRow(String name, Map<TraceOutcome, Long> verdicts) {
    return new AnalyticsView.GuardRow(
        name,
        verdicts.getOrDefault(TraceOutcome.PASS, 0L),
        verdicts.getOrDefault(TraceOutcome.BLOCK, 0L),
        verdicts.getOrDefault(TraceOutcome.REPAIR, 0L),
        verdicts.getOrDefault(TraceOutcome.OVERRIDE, 0L),
        RoutingGuards.NAMES.contains(name));
  }

  private static AnalyticsView.OpsRow opsRow(String model, List<TurnRow> turns) {
    List<Long> latencies = turns.stream().map(turn -> (long) turn.latencyMs()).toList();
    return new AnalyticsView.OpsRow(
        model,
        turns.size(),
        turns.stream().mapToLong(TurnRow::tokensIn).sum(),
        turns.stream().mapToLong(TurnRow::tokensCached).sum(),
        turns.stream().mapToLong(TurnRow::tokensOut).sum(),
        turns.stream().mapToLong(TurnRow::tokensReasoning).sum(),
        turns.stream().map(TurnRow::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add),
        Percentiles.of(latencies, 50).orElse(0),
        Percentiles.of(latencies, 95).orElse(0),
        turns.stream().filter(TurnRow::failed).count(),
        turns.stream().filter(TurnRow::retried).count());
  }

  private static <E extends Enum<E>> Map<E, Long> counted(
      List<Started> started, Function<Started, E> key, Class<E> type) {
    return started.stream()
        .collect(Collectors.groupingBy(key, () -> new EnumMap<>(type), Collectors.counting()));
  }

  /** "50% of started". */
  private static String share(long count, long started) {
    return Math.round(100.0 * count / started) + "% of started";
  }
}
