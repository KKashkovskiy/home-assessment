package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

import com.enrola.anna.web.EvalReports.RunRow;
import com.enrola.anna.web.EvalReports.TrialLine;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A run as the harness leaves it: an {@code eval_run} row in the app database, and a folder with
 * the golden report, a trial page, {@code trials.jsonl} and the gates' exit code.
 */
class EvalReportsTest extends ConsoleFixture {

  static final String RUN = "2026-10-22T10-41-03Z";

  /** A run from before trials were numbered. */
  static final String OLD = "2026-10-21T09-00-00Z";

  static final String OLD_LINE =
      """
      {"run":"2026-10-21T09-00-00Z","scenario":"turn-a","kind":"turn","status":"SKIPPED_KIND","checks":[],"judges":[],"cost_usd":0,"model_calls":0,"conversation_id":null,"turn_no":0,"duration_ms":0}
      """;

  /** A passing turn, then a story whose checks pass on turn 2 and fail from turn 3. */
  static final String TRIALS =
      """
      {"run":"%1$s","scenario":"turn-a","trial_no":1,"kind":"turn","status":"PASS","checks":[{"name":"length","gate":"HARD","pass":true,"evidence":"ok","turn":1}],"judges":[],"cost_usd":0.01,"conversation_id":"c-1","turn_no":1}
      {"run":"%1$s","scenario":"story-b","trial_no":2,"kind":"story","status":"FAIL","checks":[{"name":"length","gate":"HARD","pass":true,"turn":2},{"name":"deny","gate":"HARD","pass":false,"turn":4},{"name":"url","gate":"HARD","pass":false,"turn":3},{"name":"goal_met","gate":"EXPECTATION","pass":false,"turn":null}],"judges":[],"cost_usd":0.05,"conversation_id":"c-2","turn_no":5}
      """
          .formatted(RUN);

  @Autowired EvalReports reports;

  @Test
  void rowsReadNewestFirstWithTheirGatesAndOutcome() throws IOException {
    insertRow(RUN, "2026-10-22T10:41:03Z", "FAIL", false);
    insertRow("2026-10-22T09-00-00Z", "2026-10-22T09:00:00Z", "PASS", true);

    assertThat(reports.recent(10))
        .extracting(RunRow::id)
        .containsExactly(RUN, "2026-10-22T09-00-00Z");
    RunRow red = reports.row(RUN).orElseThrow();
    assertThat(red.gatesCode()).isEqualTo(3);
    assertThat(red.outcome()).isEqualTo(RunOutcome.RED);
    assertThat(red.duration()).isEqualTo(Duration.ofSeconds(90));
    assertThat(reports.row("2026-10-22T09-00-00Z").orElseThrow().outcome())
        .isEqualTo(RunOutcome.GREEN);
  }

  @Test
  void trialsGiveTheFailedChecksTheFirstFailingTurnAndItsTraceLink() throws IOException {
    write(RUN, "trials.jsonl", TRIALS);

    TrialLine passed = reports.trials(RUN).orElseThrow().getFirst();
    TrialLine failed = reports.trials(RUN).orElseThrow().getLast();

    assertThat(failed.failed()).isTrue();
    assertThat(failed.failedChecks()).containsExactly("deny", "url", "goal_met");
    assertThat(failed.firstFailingTurn()).isEqualTo(3);
    assertThat(EvalReports.traceUrl(RUN, failed))
        .isEqualTo("/evals/runs/" + RUN + "/conversations/c-2/turns/3");
    assertThat(passed.failed()).isFalse();
    assertThat(EvalReports.traceUrl(RUN, passed)).isNull();
  }

  @Test
  void aRunThatRecordedNoTrialsHasNone() {
    assertThat(reports.trials("2026-10-22T08-00-00Z")).contains(List.of());
  }

  /** A line as commit 15 wrote it, before trials were numbered, reads as the first trial. */
  @Test
  void aLineFromBeforeTrialNumbersIsTheFirstTrial() throws IOException {
    write(OLD, "trials.jsonl", OLD_LINE);

    assertThat(reports.trials(OLD).orElseThrow())
        .extracting(TrialLine::label, TrialLine::skipped)
        .containsExactly(tuple("turn-a#1", true));
  }

  /** A run whose lines this build cannot read is passed over, never an error page. */
  @Test
  void aRunWhoseLinesDoNotReadIsPassedOver() throws IOException {
    insertRow(RUN, "2026-10-22T10:41:03Z", "FAIL", false);
    insertRow(OLD, "2026-10-22T09:00:00Z", "PASS", true);
    write(RUN, "trials.jsonl", "{\"scenario\": [1, 2]}\n");
    write(OLD, "trials.jsonl", OLD_LINE.replace("SKIPPED_KIND", "PASS"));

    assertThat(reports.trials(RUN)).isEmpty();
    assertThat(reports.lastResults(reports.recent(10))).containsExactly(entry("turn-a", "PASS"));
  }

  @Test
  void reportRendersTheGoldenReportWithItsTrialLinksAndOutcome() throws IOException {
    write(
        RUN,
        "report.md",
        Files.readString(Path.of("src/test/resources/eval/golden-report-judges.md")));
    write(RUN, "exit-code", "3\n");

    String html = reports.reportHtml(RUN, true).orElseThrow();

    assertThat(html)
        .contains(
            "<h3>Eval run " + RUN + "</h3>",
            "<h4>Gates</h4>",
            "<table>",
            "<a href=\"/evals/runs/" + RUN + "/scenarios/turn-happy-pick-a-time?trial=2\">#2</a>",
            "<a href=\"/evals/runs/" + RUN + "/scenarios/turn-non-english\">turn-non-english</a>");
    assertThat(reports.outcome(RUN)).isEqualTo(RunOutcome.RED);
  }

  /** A phone is masked wherever it is, a heading included; the run's own id never is. */
  @Test
  void theMaskCoversEveryLineButTheRunsId() throws IOException {
    write(
        RUN,
        "story-b.2.md",
        "# Call 0412 345 678\n\n- **Lead:** call me on 0412 345 678\n\nRun " + RUN + "\n");

    assertThat(reports.trialPageHtml(RUN, "story-b", 2, true).orElseThrow())
        .contains("<h3>Call 041• ••• 678</h3>", "call me on 041• ••• 678", "Run " + RUN)
        .doesNotContain("345");
    assertThat(reports.trialPageHtml(RUN, "story-b", 2, false).orElseThrow())
        .contains("0412 345 678");
    assertThat(reports.trialPageHtml(RUN, "story-b", 1, true)).isEmpty();
  }

  @Test
  void lastResultsTakeEachScenarioFromTheNewestRunThatRanIt() throws IOException {
    insertRow(RUN, "2026-10-22T10:41:03Z", "FAIL", false);
    insertRow("2026-10-22T09-00-00Z", "2026-10-22T09:00:00Z", "PASS", true);
    write(RUN, "trials.jsonl", TRIALS);
    write(
        "2026-10-22T09-00-00Z",
        "trials.jsonl",
        TRIALS.replace("\"FAIL\"", "\"PASS\"").replace("story-b", "story-c"));

    assertThat(reports.lastResults(reports.recent(10)))
        .containsEntry("turn-a", "PASS")
        .containsEntry("story-b", "FAIL")
        .containsEntry("story-c", "PASS");
  }

  /** A run that skipped a scenario says nothing of it; the older result stands. */
  @Test
  void lastResultsPassOverTrialsThatNeverRan() throws IOException {
    insertRow(RUN, "2026-10-22T10:41:03Z", "SKIPPED", true);
    insertRow(OLD, "2026-10-22T09:00:00Z", "PASS", true);
    write(RUN, "trials.jsonl", OLD_LINE.replace("SKIPPED_KIND", "SKIPPED_NO_KEY"));
    write(OLD, "trials.jsonl", OLD_LINE.replace("SKIPPED_KIND", "PASS"));

    assertThat(reports.lastResults(reports.recent(10))).containsExactly(entry("turn-a", "PASS"));
  }

  private void insertRow(String id, String started, String verdict, boolean green) {
    jdbc.update(
        "INSERT INTO eval_run (id, started_at, finished_at, prompt_version, model, offline,"
            + " selected, passed, failed, invalid, skipped, verdict, cost_usd, report_path,"
            + " trials, gate_hard, gate_global) VALUES (?, CAST(? AS TIMESTAMP WITH TIME ZONE),"
            + " DATEADD(SECOND, 90, CAST(? AS TIMESTAMP WITH TIME ZONE)), 'v1', 'gpt-5.1', FALSE,"
            + " 2, 1, 1, 0, 0, ?, ?, 'r', 1, ?, ?)",
        id,
        started,
        started,
        verdict,
        new BigDecimal("0.0600"),
        green,
        green);
  }

  private static void write(String runId, String name, String text) throws IOException {
    Path folder = Files.createDirectories(EVALS.resolve("runs").resolve(runId));
    Files.writeString(folder.resolve(name), text);
  }
}
