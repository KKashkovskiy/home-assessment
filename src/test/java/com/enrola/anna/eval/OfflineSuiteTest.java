package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every scenario file on the stand-in model, inside the normal build and prepared as the CLI
 * prepares a run: each turn passes, stories are skipped, the report files exist, and the {@code
 * eval_run} row lands in the app database, here the fixture's own. A failing scenario is named with
 * its failed checks and their evidence.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfflineSuiteTest extends EvalFixture {

  static final Path RUN = DATA.resolve("runs/offline");

  @Autowired EvalRuns runs;

  List<Scenario> selected;
  List<Trial> trials;

  @BeforeAll
  void runEveryScenario() throws IOException {
    noConversations();
    EvalRun run =
        runs.prepare(
            new EvalOptions("offline", Selection.ALL, new RunMode.Offline(), null, RUN, APP_DB));
    selected = run.begin();
    selected.forEach(run::trial);
    run.finish();
    trials = run.trials();
  }

  @AfterAll
  void forgetTheRun() {
    jdbc.update("delete from eval_run where id = 'offline'");
  }

  @Test
  void everyTurnScenarioPassesOffline() {
    assertThat(trials.stream().filter(trial -> trial.kind().equals("turn")))
        .hasSizeGreaterThanOrEqualTo(41)
        .allSatisfy(
            trial ->
                assertThat(trial.status())
                    .as("%s failed: %s", trial.scenarioId(), failures(trial))
                    .isEqualTo(TrialStatus.PASS));
  }

  @Test
  void storiesAreSkipped() {
    assertThat(trials.stream().filter(trial -> trial.kind().equals("story")))
        .isNotEmpty()
        .allSatisfy(trial -> assertThat(trial.status()).isEqualTo(TrialStatus.SKIPPED_KIND));
  }

  @Test
  void reportFilesExist() throws IOException {
    assertThat(Files.readString(RUN.resolve("report.md")))
        .contains(
            "## Gates\nhard: PASS",
            "## Coverage by tag",
            "## Scenarios",
            "## Cost",
            "| Offline stand-in | yes",
            "| Verdict | PASS |");
    assertThat(Files.readAllLines(RUN.resolve("trials.jsonl"))).hasSize(selected.size());
    assertThat(selected)
        .allSatisfy(scenario -> assertThat(RUN.resolve(scenario.id() + ".md")).exists());
  }

  @Test
  void evalRunRowWritten() {
    Map<String, Object> row =
        jdbc.queryForMap(
            "select selected, passed, skipped, offline, verdict from eval_run where id = 'offline'");

    assertThat(row)
        .containsEntry("SELECTED", selected.size())
        .containsEntry("OFFLINE", true)
        .containsEntry("VERDICT", "PASS")
        .containsEntry("PASSED", selected.size() - (int) row.get("SKIPPED"));
  }

  private static String failures(Trial trial) {
    return trial.checks().stream()
        .filter(check -> !check.pass())
        .map(check -> check.name() + " (" + check.evidence() + ")")
        .toList()
        .toString();
  }
}
