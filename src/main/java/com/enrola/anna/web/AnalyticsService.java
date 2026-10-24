package com.enrola.anna.web;

import com.enrola.anna.AppClock;
import com.enrola.anna.web.AnalyticsQueries.Cohort;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The analytics page, read fresh for every request: every query for one filter runs once into
 * {@link AnalyticsRows}, and the tiles ({@link AnalyticsKpis}), charts and tables ({@link
 * AnalyticsPanels}) are computed from those rows; two prompt versions compare face to face on the
 * same tiles.
 */
@Service
public class AnalyticsService {

  private final AnalyticsQueries queries;
  private final AnalyticsPanels panels;
  private final AppClock clock;

  public AnalyticsService(AnalyticsQueries queries, AnalyticsPanels panels, AppClock clock) {
    this.queries = queries;
    this.panels = panels;
    this.clock = clock;
  }

  /**
   * Tiles, charts, guard verdicts and model operations for {@code filter}, the charts' days in
   * {@code advisorZone}.
   */
  public AnalyticsView view(AnalyticsFilter filter, ZoneId advisorZone) {
    AnalyticsRows rows = read(filter);
    return new AnalyticsView(
        rows.since(),
        rows.started().size(),
        AnalyticsKpis.of(rows),
        AnalyticsKpis.handoffReasons(rows),
        panels.charts(rows, advisorZone),
        AnalyticsPanels.guards(rows.guardCounts()),
        AnalyticsPanels.ops(rows.turns()));
  }

  /** The charts alone, for the script that draws them. */
  public List<Series> charts(AnalyticsFilter filter, ZoneId advisorZone) {
    return panels.charts(read(filter), advisorZone);
  }

  /**
   * The tiles for conversations started on {@code a} beside those started on {@code b}, the rest of
   * {@code filter} applied to both, and the eval pass^k of each version.
   */
  public CompareView compare(String a, String b, AnalyticsFilter filter) {
    AnalyticsRows onA = read(filter.withPromptVersion(a));
    AnalyticsRows onB = read(filter.withPromptVersion(b));
    Map<String, Kpi> kpisOnB =
        AnalyticsKpis.of(onB).stream().collect(Collectors.toMap(Kpi::key, Function.identity()));
    List<CompareView.Row> rows = new ArrayList<>();
    for (Kpi kpi : AnalyticsKpis.of(onA)) {
      rows.add(CompareView.row(kpi, kpisOnB.get(kpi.key())));
    }
    rows.add(CompareView.evalPassRow(evalPassK(a), evalPassK(b)));
    return new CompareView(a, b, onA.started().size(), onB.started().size(), rows);
  }

  /**
   * pass^k of the latest keyed eval run on {@code promptVersion} that judged a scenario: its clean
   * scenarios over its judged ones, linking to its report on the Evals page; empty while the
   * version has none.
   */
  public Optional<Kpi> evalPassK(String promptVersion) {
    return queries
        .latestKeyedEvalRun(promptVersion)
        .map(
            run ->
                new Kpi(
                    CompareView.EVAL_PASS,
                    CompareView.EVAL_PASS_LABEL,
                    run.clean(),
                    run.judged(),
                    Kpi.Unit.RATE,
                    reportUrl(run.id()),
                    null,
                    Kpi.Better.UP));
  }

  /** Every model a conversation has started on. */
  public List<String> models() {
    return queries.models();
  }

  /**
   * The run's report on the Evals page, or null for an id that page does not take, such as one a
   * run started straight from Maven was given.
   */
  private static String reportUrl(String runId) {
    if (!runId.matches(EvalReports.RUN_ID)) {
      return null;
    }
    return UriComponentsBuilder.fromPath(EvalsController.PATH)
        .queryParam("run", runId)
        .fragment("report-panel")
        .toUriString();
  }

  private AnalyticsRows read(AnalyticsFilter filter) {
    Instant now = clock.now();
    Instant since = filter.since(now);
    Cohort cohort = Cohort.of(filter, since);
    return new AnalyticsRows(
        now,
        since,
        cohort,
        filter.toConversationFilter(now),
        queries.conversations(cohort),
        queries.turns(cohort),
        queries.violations(cohort),
        queries.guardCounts(cohort),
        queries.objections(cohort));
  }
}
