package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run's branches with fake runners at $0.04 a trial and $0.05 a typical call: what each mode
 * runs, the cap before and during the run, k trials, the gates' exit code, and a trial that
 * crashes. The app database does not exist here, so no row is written and the report says so.
 */
class EvalRunTest {

  static final Scenario.Turn FIRST =
      ScenarioFixtures.turn("turn-first", "hi", Scenario.Expect.NONE, "core");
  static final Scenario.Turn SECOND =
      ScenarioFixtures.turn("turn-second", "hi", Scenario.Expect.NONE);
  static final Scenario.Turn THIRD =
      ScenarioFixtures.turn("turn-third", "hi", Scenario.Expect.NONE);
  static final Scenario.Story STORY = ScenarioFixtures.story("story-lauren-books");

  @TempDir Path dir;

  final List<String> ran = new ArrayList<>();
  EvalBudget budget;

  /** Two turns at two calls and a story at fourteen: eighteen calls at $0.05. */
  @Test
  void dryRunPlansAndRunsNothing() throws IOException {
    EvalRun run = run(new RunMode.DryRun(), "10", 1, this::pass, FIRST, SECOND, STORY);

    assertThat(run.begin()).isEmpty();
    run.finish();

    assertThat(Files.readString(dir.resolve("report.md")))
        .contains("(dry run)", "Estimate: $0.9000 of $10.00 cap for 3 scenarios (all) x 1 trials");
    assertThat(run.trials()).isEmpty();
  }

  @Test
  void withoutAKeyEveryTrialIsSkippedAndTheReportWritten() throws IOException {
    EvalRun run = run(new RunMode.NoKey(), "10", 2, this::pass, FIRST, STORY);

    assertThat(run.begin()).isEmpty();
    GateResult gates = run.finish();

    assertThat(run.trials())
        .extracting(Trial::status)
        .containsOnly(TrialStatus.SKIPPED_NO_KEY)
        .hasSize(4);
    assertThat(gates.exitCode()).isZero();
    assertThat(Files.readString(dir.resolve("report.md")))
        .contains("| Verdict | SKIPPED |", "hard: SKIPPED", "No key", "No eval_run row");
  }

  /** Two turns are estimated at $0.20, over a $0.10 cap: nothing runs, and the report says why. */
  @Test
  void keyedRunOverTheCapIsRefusedBeforeAnyCall() throws IOException {
    EvalRun run = run(new RunMode.Keyed("eval"), "0.10", 1, this::pass, FIRST, SECOND);

    assertThatThrownBy(run::begin)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("over the $0.10 cap");
    assertThat(ran).isEmpty();
    assertThat(run.trials()).extracting(Trial::status).containsOnly(TrialStatus.SKIPPED_BUDGET);
    assertThat(Files.readString(dir.resolve("report.md")))
        .contains("Refused: the estimate $0.2000");
  }

  /** The estimate counts every trial: one turn at $0.10 is $0.20 at two trials. */
  @Test
  void theEstimateCountsTrials() {
    EvalRun run = run(new RunMode.Keyed("eval"), "0.15", 2, this::pass, FIRST);

    assertThatThrownBy(run::begin).hasMessageContaining("estimate $0.2000");
  }

  /** At $0.08 spent of $0.10, one more $0.05 call would pass the cap, so the third skips. */
  @Test
  void capReachedMidRunSkipsTheRest() {
    EvalRun run = run(new RunMode.Offline(), "0.10", 1, this::pass, FIRST, SECOND, THIRD);

    run.begin().forEach(scenario -> run.trial(scenario, 1));

    assertThat(run.trials())
        .extracting(Trial::status)
        .containsExactly(TrialStatus.PASS, TrialStatus.PASS, TrialStatus.SKIPPED_BUDGET);
    assertThat(ran).containsExactly("turn-first#1", "turn-second#1");
  }

  /**
   * A story's runner minds the budget line by line, so the run hands it every trial, and its spend
   * joins the ledger.
   */
  @Test
  void storiesGoToTheirRunnerWhateverIsLeft() {
    EvalRun run = run(new RunMode.Offline(), "0.01", 1, this::pass, STORY);

    run.trial(run.begin().getFirst(), 1);

    assertThat(ran).containsExactly("story-lauren-books#1");
    assertThat(budget.spent()).isEqualByComparingTo("0.04");
  }

  /** The gates decide: a core FAIL is exit 2 and the verdict FAIL, though nothing crashed. */
  @Test
  void finishWritesTheGatesExitCode() throws IOException {
    EvalRun run = run(new RunMode.Offline(), "10", 2, this::failFirstTrial, FIRST);

    run.begin().forEach(scenario -> List.of(1, 2).forEach(k -> run.trial(scenario, k)));

    assertThat(run.finish().exitCode()).isEqualTo(2);
    assertThat(Files.readString(dir.resolve(ReportWriter.EXIT_CODE))).isEqualTo("2\n");
    assertThat(Files.readString(dir.resolve("report.md"))).contains("| Verdict | FAIL |");
  }

  /** A trial that throws is never recorded, and the run fails though the rest passed. */
  @Test
  void aCrashedTrialFailsTheRun() throws IOException {
    EvalRun run =
        run(
            new RunMode.Offline(),
            "10",
            1,
            (turn, trialNo) -> {
              if (turn.equals(SECOND)) {
                throw new IllegalStateException("boom");
              }
              return pass(turn, trialNo);
            },
            FIRST,
            SECOND);

    List<Scenario> selected = run.begin();
    run.trial(selected.getFirst(), 1);
    assertThatThrownBy(() -> run.trial(selected.get(1), 1)).hasMessage("boom");
    run.finish();

    assertThat(Files.readString(dir.resolve("report.md")))
        .contains("| Trials | 1 run, 0 skipped, 1 crashed |", "| Verdict | FAIL |");
  }

  private EvalRun run(
      RunMode mode,
      String cap,
      int trials,
      EvalRun.Runner<Scenario.Turn> turns,
      Scenario... selected) {
    var header =
        new RunHeader(
            "test", "v1", "m", "low", mode, "all", trials, 0.2, new BigDecimal(cap), Instant.EPOCH);
    String missing = "jdbc:h2:file:" + dir.resolve("no-app/anna");
    budget = new EvalBudget(new BigDecimal(cap), EvalBudgetTest.FIVE_CENTS, "m");
    return new EvalRun(
        turns, this::pass, budget, new RunPlan(header, List.of(selected), dir, missing));
  }

  private Trial pass(Scenario scenario, int trialNo) {
    return ran(scenario, trialNo, TrialStatus.PASS);
  }

  private Trial failFirstTrial(Scenario scenario, int trialNo) {
    return ran(scenario, trialNo, trialNo == 1 ? TrialStatus.FAIL : TrialStatus.PASS);
  }

  private Trial ran(Scenario scenario, int trialNo, TrialStatus status) {
    ran.add(scenario.id() + "#" + trialNo);
    return new Trial(
        scenario.id(),
        trialNo,
        status,
        List.of(),
        List.of(),
        new BigDecimal("0.04"),
        1,
        "c-" + scenario.id(),
        ScenarioFixtures.trial(scenario, trialNo, status).detail(),
        10);
  }
}
