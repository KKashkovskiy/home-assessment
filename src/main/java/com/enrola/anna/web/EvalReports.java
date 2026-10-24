package com.enrola.anna.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the Evals page shows of finished runs: the {@code eval_run} rows of the app database, and
 * each run's folder under {@code evals/runs/<id>/}: its report and per-trial pages rendered, its
 * {@code trials.jsonl} read line by line, and its {@code exit-code} file. With the PII mask on,
 * phone- and email-shaped text is masked everywhere but in the run's own id.
 */
@Component
public class EvalReports {

  private static final Logger log = LoggerFactory.getLogger(EvalReports.class);

  /** A run id as {@code run.sh} and the page mint them: the UTC time, and a counter on a clash. */
  public static final String RUN_ID = "\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}Z(-\\d{1,4})?";

  /**
   * A per-trial page as {@code report.md} links it: the first trial's, then {@code <id>.<k>.md}.
   */
  private static final Pattern TRIAL_PAGE =
      Pattern.compile("(" + ScenarioFiles.ID + ")(?:\\.(\\d{1,3}))?\\.md");

  /** The report header's verdict row, which a dry run's plan does not have. */
  private static final Pattern VERDICT =
      Pattern.compile("^\\| Verdict \\| (PASS|FAIL|SKIPPED) \\|$", Pattern.MULTILINE);

  /** One {@code eval_run} row: counts are trials; a share is null when nothing was judged. */
  public record RunRow(
      String id,
      Instant startedAt,
      Instant finishedAt,
      String promptVersion,
      String model,
      boolean offline,
      int trials,
      int selected,
      int passed,
      int failed,
      int invalid,
      int skipped,
      String verdict,
      BigDecimal costUsd,
      String judgeModel,
      BigDecimal judgeCostUsd,
      BigDecimal passK,
      BigDecimal wilsonLow,
      BigDecimal wilsonHigh,
      boolean gateHard,
      boolean gateGlobal,
      boolean gateValidity) {

    /** How one gate went; one a run skipped whole never ran. */
    public enum Gate {
      PASS,
      FAIL,
      NOT_RUN
    }

    public Gate hard() {
      return gate(gateHard);
    }

    public Gate global() {
      return gate(gateGlobal);
    }

    public Gate validity() {
      return gate(gateValidity);
    }

    private Gate gate(boolean passed) {
      if ("SKIPPED".equals(verdict)) {
        return Gate.NOT_RUN;
      }
      return passed ? Gate.PASS : Gate.FAIL;
    }

    /** From the row's verdict alone; {@link EvalReports#outcome} also reads the run's folder. */
    public RunOutcome outcome() {
      return RunOutcome.of(new RunOutcome.Evidence(verdict, null, true));
    }

    public Duration duration() {
      return finishedAt == null ? null : Duration.between(startedAt, finishedAt);
    }

    /**
     * Whether the page reads and links this run: not one started straight from Maven under an id of
     * its own, which {@link #RUN_ID} does not take.
     */
    public boolean opens() {
      return id.matches(RUN_ID);
    }
  }

  /**
   * One {@code trials.jsonl} line, as far as the page reads it. Lines from before trials (commit
   * 15) carry no trial number: each was the scenario's first.
   */
  public record TrialLine(
      String scenario,
      Integer trialNo,
      String status,
      List<CheckLine> checks,
      String conversationId) {

    public TrialLine {
      trialNo = trialNo == null ? 1 : trialNo;
      checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public boolean skipped() {
      return status.startsWith("SKIPPED");
    }

    /** PASS, or a trial that did not run, which proves nothing either way. */
    public boolean failed() {
      return status.equals("FAIL") || status.equals("INVALID");
    }

    public String label() {
      return scenario + "#" + trialNo;
    }

    /** The names of the checks that failed, each once, in the order they ran. */
    public List<String> failedChecks() {
      return checks.stream()
          .filter(check -> !check.pass())
          .map(CheckLine::name)
          .distinct()
          .toList();
    }

    /** The names of the checks that failed about turn {@code turnNo}, each once. */
    public List<String> failedChecksAt(int turnNo) {
      return checks.stream()
          .filter(check -> !check.pass() && Objects.equals(check.turn(), turnNo))
          .map(CheckLine::name)
          .distinct()
          .toList();
    }

    /** The earliest turn a failed check is about; null when none is about a turn. */
    public Integer firstFailingTurn() {
      return checks.stream()
          .filter(check -> !check.pass())
          .map(CheckLine::turn)
          .filter(Objects::nonNull)
          .min(Integer::compare)
          .orElse(null);
    }
  }

  /** One check of a trial: its name, whether it held, and the turn it is about, if one. */
  public record CheckLine(String name, boolean pass, Integer turn) {}

  private final JdbcClient db;
  private final JsonMapper json;
  private final Path runs;

  public EvalReports(JdbcClient db, JsonMapper json, @Value("${anna.evals-dir}") Path evalsDir) {
    this.db = db;
    this.json = json;
    this.runs = evalsDir.toAbsolutePath().normalize().resolve("runs");
  }

  /** The latest {@code limit} rows, newest first. */
  public List<RunRow> recent(int limit) {
    return db.sql("SELECT * FROM eval_run ORDER BY started_at DESC LIMIT :limit")
        .param("limit", limit)
        .query(RunRow.class)
        .list();
  }

  public Optional<RunRow> row(String runId) {
    return db.sql("SELECT * FROM eval_run WHERE id = :id")
        .param("id", runId)
        .query(RunRow.class)
        .optional();
  }

  /**
   * How the run ended, from its exit-code file and its verdict: the row's, or the report header's
   * when the row did not land.
   */
  public RunOutcome outcome(String runId) {
    Optional<String> report = text(runId, "report.md");
    return RunOutcome.of(
        new RunOutcome.Evidence(
            row(runId)
                .map(RunRow::verdict)
                .or(() -> report.flatMap(EvalReports::reportVerdict))
                .orElse(null),
            text(runId, "exit-code").map(code -> Integer.valueOf(code.strip())).orElse(null),
            report.isPresent()));
  }

  /** The run's {@code report.md} as HTML, its trial links to the trial pages; empty without one. */
  public Optional<String> reportHtml(String runId, boolean mask) {
    return text(runId, "report.md").map(markdown -> html(runId, markdown, mask));
  }

  /** Trial {@code trialNo}'s page of the scenario as HTML; empty when the run has no such page. */
  public Optional<String> trialPageHtml(
      String runId, String scenarioId, int trialNo, boolean mask) {
    String page = trialNo == 1 ? scenarioId + ".md" : scenarioId + "." + trialNo + ".md";
    return text(runId, page).map(markdown -> html(runId, markdown, mask));
  }

  /**
   * Every trial the run recorded, in run order, none for a run that recorded none; empty when its
   * lines do not read as this build's, so an old or broken run is passed over, never an error.
   */
  public Optional<List<TrialLine>> trials(String runId) {
    try {
      return Optional.of(
          text(runId, "trials.jsonl").stream()
              .flatMap(String::lines)
              .filter(line -> !line.isBlank())
              .map(line -> json.readValue(line, TrialLine.class))
              .toList());
    } catch (JacksonException e) {
      log.warn("run {}: trials.jsonl does not read ({})", runId, e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * Each scenario's result in the newest of {@code rows} that ran it: FAIL when a trial failed,
   * else PASS when one passed, else the first trial's status. A trial that never ran says nothing
   * of the scenario, so a run that skipped it leaves its older result; nor does a run the page does
   * not {@linkplain RunRow#opens open}.
   */
  public Map<String, String> lastResults(List<RunRow> rows) {
    Map<String, String> results = new HashMap<>();
    for (RunRow row : rows.stream().filter(RunRow::opens).toList()) {
      trials(row.id()).orElse(List.of()).stream()
          .filter(trial -> !trial.skipped())
          .collect(
              Collectors.groupingBy(TrialLine::scenario, LinkedHashMap::new, Collectors.toList()))
          .forEach((scenario, trials) -> results.putIfAbsent(scenario, result(trials)));
    }
    return results;
  }

  /** The inspector link for a failed trial's first failing turn; null when there is none. */
  public static String traceUrl(String runId, TrialLine trial) {
    if (trial.firstFailingTurn() == null || trial.conversationId() == null) {
      return null;
    }
    return "/evals/runs/%s/conversations/%s/turns/%d"
        .formatted(runId, trial.conversationId(), trial.firstFailingTurn());
  }

  /** The console's page for a trial page {@code report.md} links to; null for any other target. */
  static String trialUrl(String runId, String target) {
    Matcher page = TRIAL_PAGE.matcher(target);
    if (!page.matches()) {
      return null;
    }
    int trialNo = page.group(3) == null ? 1 : Integer.parseInt(page.group(3));
    return EvalsPage.trialPageUrl(runId, page.group(1), trialNo);
  }

  private static Optional<String> reportVerdict(String report) {
    Matcher verdict = VERDICT.matcher(report);
    return verdict.find() ? Optional.of(verdict.group(1)) : Optional.empty();
  }

  private static String result(List<TrialLine> trials) {
    if (trials.stream().anyMatch(trial -> trial.status().equals("FAIL"))) {
      return "FAIL";
    }
    return trials.stream().anyMatch(trial -> trial.status().equals("PASS"))
        ? "PASS"
        : trials.getFirst().status();
  }

  private static String html(String runId, String markdown, boolean mask) {
    Function<String, String> links = target -> trialUrl(runId, target);
    return MarkdownLite.render(mask ? masked(runId, markdown) : markdown, links);
  }

  /**
   * Every line masked, a line at a time so no run of digits spans two, but the run's own id, the
   * one phone-shaped thing the harness writes.
   */
  private static String masked(String runId, String markdown) {
    Pattern id = Pattern.compile(Pattern.quote(runId));
    return markdown
        .lines()
        .map(
            line ->
                Arrays.stream(id.split(line, -1))
                    .map(PiiMask::maskText)
                    .collect(Collectors.joining(runId)))
        .collect(Collectors.joining("\n"));
  }

  /** A file of the run's folder; empty when it is not there, an error when it cannot be read. */
  private Optional<String> text(String runId, String name) {
    try {
      return Optional.of(Files.readString(folder(runId).resolve(name)));
    } catch (NoSuchFileException e) {
      return Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + name + " of run " + runId, e);
    }
  }

  private Path folder(String runId) {
    if (!runId.matches(RUN_ID)) {
      throw new IllegalArgumentException("not a run id");
    }
    return runs.resolve(runId);
  }
}
