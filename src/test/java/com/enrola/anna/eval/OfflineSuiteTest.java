package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every scenario file on the stand-ins, two trials each, inside the normal build and prepared as
 * the CLI prepares a run: every turn and story trial passes, pass^k is 100%, the gates are green,
 * the report files exist, and the {@code eval_run} row with its statistics lands in the app
 * database, here the fixture's own. A failing trial is named with its failed checks and their
 * evidence.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfflineSuiteTest extends EvalFixture {

  static final Path RUN = DATA.resolve("runs/offline");
  static final int TRIALS = 2;

  @Autowired EvalRuns runs;

  List<Scenario> selected;
  List<Trial> trials;
  GateResult gates;

  @BeforeAll
  void runEveryScenarioTwice() throws IOException {
    noConversations();
    EvalRun run =
        runs.prepare(
            new EvalOptions(
                "offline",
                Selection.ALL,
                new RunMode.Offline(),
                null,
                TRIALS,
                EvalOptions.VALIDITY_THRESHOLD,
                RUN,
                APP_DB));
    selected = run.begin();
    selected.forEach(
        scenario -> IntStream.rangeClosed(1, TRIALS).forEach(k -> run.trial(scenario, k)));
    gates = run.finish();
    trials = run.trials();
  }

  @AfterAll
  void forgetTheRun() {
    jdbc.update("delete from eval_run where id = 'offline'");
  }

  @Test
  void everyTurnScenarioPassesOffline() {
    assertThat(trials.stream().filter(trial -> trial.kind().equals("turn")))
        .hasSizeGreaterThanOrEqualTo(41 * TRIALS)
        .allSatisfy(OfflineSuiteTest::passed);
  }

  /** Each story talks past its opener, and every one that ended was probed. */
  @Test
  void everyStoryPassesOfflineWithTwoTrials() {
    assertThat(trials.stream().filter(trial -> trial.kind().equals("story")))
        .hasSize(8 * TRIALS)
        .allSatisfy(OfflineSuiteTest::passed)
        .allSatisfy(
            trial ->
                assertThat(((Trial.StoryDetail) trial.detail()).transcript()).hasSizeGreaterThan(2))
        .allSatisfy(trial -> assertThat(trial.stopReason()).contains("2 probes sent"));
  }

  @Test
  void statisticsAndGatesAreGreen() {
    assertThat(new RunSummary(trials, trials.size(), gates).passK()).isEqualTo(1.0);
    assertThat(gates.exitCode()).isZero();
  }

  @Test
  void reportFilesExist() throws IOException {
    assertThat(Files.readString(RUN.resolve("report.md")))
        .contains(
            "| Trials per scenario | 2 |",
            "| Pass^k | 100% ("
                + selected.size()
                + " of "
                + selected.size()
                + " scenarios clean) |",
            "## Gates\nhard: PASS",
            "global: PASS",
            "validity: PASS",
            "exit code: 0",
            "## Coverage by tag",
            "## Scenarios",
            "## Cost",
            "| Offline stand-in | yes",
            "| Verdict | PASS |");
    assertThat(RUN.resolve(ReportWriter.EXIT_CODE)).hasContent("0");
    assertThat(Files.readAllLines(RUN.resolve("trials.jsonl"))).hasSize(selected.size() * TRIALS);
    assertThat(selected)
        .allSatisfy(
            scenario -> {
              assertThat(RUN.resolve(scenario.id() + ".md")).exists();
              assertThat(RUN.resolve(scenario.id() + ".2.md")).exists();
            });
  }

  @Test
  void evalRunRowWritten() {
    Map<String, Object> row =
        jdbc.queryForMap(
            "select selected, passed, skipped, offline, verdict, trials, pass_k, wilson_high,"
                + " gate_hard, gate_global, gate_validity from eval_run where id = 'offline'");

    assertThat(row)
        .containsEntry("SELECTED", selected.size() * TRIALS)
        .containsEntry("PASSED", selected.size() * TRIALS)
        .containsEntry("SKIPPED", 0)
        .containsEntry("OFFLINE", true)
        .containsEntry("VERDICT", "PASS")
        .containsEntry("TRIALS", TRIALS)
        .containsEntry("GATE_HARD", true)
        .containsEntry("GATE_GLOBAL", true)
        .containsEntry("GATE_VALIDITY", true);
    assertThat((BigDecimal) row.get("PASS_K")).isEqualByComparingTo("1");
    assertThat((BigDecimal) row.get("WILSON_HIGH")).isEqualByComparingTo("1");
  }

  private static void passed(Trial trial) {
    assertThat(trial.status())
        .as("%s failed: %s", trial.label(), failures(trial))
        .isEqualTo(TrialStatus.PASS);
  }

  private static String failures(Trial trial) {
    return trial.checks().stream()
        .filter(check -> !check.pass())
        .map(check -> check.name() + " (" + check.evidence() + ")")
        .toList()
        .toString();
  }
}
