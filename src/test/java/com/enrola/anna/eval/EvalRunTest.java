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
 * runs, the cap before and during the run, k trials, the run's exit code, a trial that crashes, and
 * the judges asked after the trials at $0.01 a typical judge call. The app database does not exist
 * here, so no row is written and the report says so.
 */
class EvalRunTest {

  static final Scenario.Turn FIRST =
      ScenarioFixtures.turn("turn-first", "hi", Scenario.Expect.NONE, "core");
  static final Scenario.Turn SECOND =
      ScenarioFixtures.turn("turn-second", "hi", Scenario.Expect.NONE);
  static final Scenario.Turn THIRD =
      ScenarioFixtures.turn("turn-third", "hi", Scenario.Expect.NONE);
  static final Scenario.Turn OTHER_CORE =
      ScenarioFixtures.turn("turn-other", "hi", Scenario.Expect.NONE, "core");
  static final Scenario.Story STORY = ScenarioFixtures.story("story-lauren-books");

  @TempDir Path dir;

  final List<String> ran = new ArrayList<>();
  EvalBudget budget;

  /** No rubric and so no question unless a test sets them, with what its judge answers. */
  List<Rubric> rubrics = List.of();

  EvalRun.Judge judge =
      (scenario, trial, question) -> {
        throw new AssertionError("no judge in this run");
      };

  /** Two turns at two calls and a story at fourteen: eighteen calls at $0.05. */
  @Test
  void dryRunPlansAndRunsNothing() throws IOException {
    EvalRun run = run(new RunMode.DryRun(), "10", 1, this::pass, FIRST, SECOND, STORY);

    assertThat(run.begin()).isEmpty();
    assertThat(run.finish()).isZero();

    assertThat(Files.readString(dir.resolve("report.md")))
        .contains("(dry run)", "Estimate: $0.9000 of $10.00 cap for 3 scenarios (all) x 1 trials");
    assertThat(run.trials()).isEmpty();
  }

  /** Without a key every trial is skipped and the report written, and the run is never green. */
  @Test
  void nothingRanExitsEight() throws IOException {
    EvalRun run = run(new RunMode.NoKey(), "10", 2, this::pass, FIRST, STORY);

    assertThat(run.begin()).isEmpty();
    assertThat(run.finish()).isEqualTo(8);

    assertThat(run.trials())
        .extracting(Trial::status)
        .containsOnly(TrialStatus.SKIPPED_NO_KEY)
        .hasSize(4);
    assertThat(Files.readString(dir.resolve(ReportWriter.EXIT_CODE))).isEqualTo("8\n");
    assertThat(Files.readString(dir.resolve("report.md")))
        .contains(
            "| Verdict | SKIPPED |",
            "| Judges | none (no key) |",
            "hard: SKIPPED",
            "exit code: 8   (no trial judged)",
            "No key",
            "No eval_run row");
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
    assertThat(Files.readString(dir.resolve(ReportWriter.EXIT_CODE))).isEqualTo("8\n");
  }

  /** The estimate counts every trial: one turn at $0.10 is $0.20 at two trials. */
  @Test
  void theEstimateCountsTrials() {
    EvalRun run = run(new RunMode.Keyed("eval"), "0.15", 2, this::pass, FIRST);

    assertThatThrownBy(run::begin).hasMessageContaining("estimate $0.2000");
  }

  /**
   * At $0.08 spent of $0.10, one more $0.05 call would pass the cap, so the third skips; the run
   * judged two, so it exits 0.
   */
  @Test
  void capReachedMidRunSkipsTheRest() {
    EvalRun run = run(new RunMode.Offline(), "0.10", 1, this::pass, FIRST, SECOND, THIRD);

    run.begin().forEach(scenario -> run.trial(scenario, 1));

    assertThat(run.trials())
        .extracting(Trial::status)
        .containsExactly(TrialStatus.PASS, TrialStatus.PASS, TrialStatus.SKIPPED_BUDGET);
    assertThat(ran).containsExactly("turn-first#1", "turn-second#1");
    assertThat(run.finish()).isZero();
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

    assertThat(run.finish()).isEqualTo(2);
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

  /**
   * Judges are asked once every trial ran, and every one failing changes nothing a gate reads: the
   * trials still pass and the exit code stays 0.
   */
  @Test
  void judgesAnswerAfterTheTrialsAndNeverGate() throws IOException {
    judgedBy(
        (scenario, trial, question) -> {
          ran.add("judge " + question.rubric().id() + " " + trial.label());
          return ScenarioFixtures.answer(question, trial, JudgeResult.Verdict.FAIL, "0.01");
        });
    EvalRun run = run(new RunMode.Keyed("eval"), "10", 1, this::pass, FIRST, OTHER_CORE);

    run.begin().forEach(scenario -> run.trial(scenario, 1));
    int exitCode = run.finish();

    assertThat(ran)
        .containsExactly(
            "turn-first#1", "turn-other#1", "judge J-ALL turn-first#1", "judge J-ALL turn-other#1");
    assertThat(run.trials()).extracting(Trial::status).containsOnly(TrialStatus.PASS);
    assertThat(run.trials())
        .flatExtracting(Trial::judges)
        .extracting(JudgeResult::verdict)
        .containsOnly(JudgeResult.Verdict.FAIL);
    assertThat(exitCode).isZero();
    assertThat(Files.readString(dir.resolve(ReportWriter.EXIT_CODE))).isEqualTo("0\n");
    assertThat(budget.spent()).isEqualByComparingTo("0.10");
  }

  /** A judge that throws costs the run nothing: its answer is UNKNOWN and the report is written. */
  @Test
  void aCrashingJudgeIsUnknownAndTheRunStillReports() throws IOException {
    judgedBy(
        (scenario, trial, question) -> {
          throw new IllegalStateException("no bean");
        });
    EvalRun run = run(new RunMode.Keyed("eval"), "10", 1, this::pass, FIRST);

    run.trial(run.begin().getFirst(), 1);
    int exitCode = run.finish();

    assertThat(run.trials().getFirst().judges())
        .singleElement()
        .satisfies(
            answer -> {
              assertThat(answer.verdict()).isEqualTo(JudgeResult.Verdict.UNKNOWN);
              assertThat(answer.note()).isEqualTo("judge crashed: IllegalStateException");
            });
    assertThat(exitCode).isZero();
    assertThat(Files.readString(dir.resolve(ReportWriter.EXIT_CODE))).isEqualTo("0\n");
    assertThat(dir.resolve("report.md")).exists();
  }

  /**
   * At $0.04 for the trial and $0.11 for the first answer of a $0.15 cap, a second $0.01 judge call
   * no longer fits, so it is not asked.
   */
  @Test
  void judgesStopAtTheCap() {
    rubrics =
        List.of(
            ScenarioFixtures.rubric("J-ONE", Rubric.Scope.TRANSCRIPT, "core"),
            ScenarioFixtures.rubric("J-TWO", Rubric.Scope.TRANSCRIPT, "core"));
    judgedBy(
        (scenario, trial, question) ->
            ScenarioFixtures.answer(question, trial, JudgeResult.Verdict.PASS, "0.11"));
    EvalRun run = run(new RunMode.Keyed("eval"), "0.15", 1, this::pass, FIRST);

    run.trial(run.begin().getFirst(), 1);
    run.finish();

    assertThat(run.trials().getFirst().judges())
        .extracting(JudgeResult::verdict)
        .containsExactly(JudgeResult.Verdict.PASS, JudgeResult.Verdict.SKIPPED_BUDGET);
  }

  /** The plan prices two turns at $0.20 against the cap, and their two judge questions apart. */
  @Test
  void dryRunEstimatesTheJudgesApart() throws IOException {
    judgedBy(
        (scenario, trial, question) -> {
          throw new AssertionError("a dry run asks no judge");
        });
    EvalRun run = run(new RunMode.DryRun(), "10", 1, this::pass, FIRST, OTHER_CORE);

    run.begin();
    run.finish();

    assertThat(Files.readString(dir.resolve("report.md")))
        .contains(
            "Estimate: $0.2000 of $10.00 cap",
            "Judges: advisory, model m (effort low): at most 2 questions, $0.0200, asked only"
                + " while the cap has room.");
  }

  private EvalRun run(
      RunMode mode,
      String cap,
      int trials,
      EvalRun.Runner<Scenario.Turn> turns,
      Scenario... selected) {
    var header =
        new RunHeader(
            "test",
            "v1",
            "m",
            "low",
            JudgeMode.of(true, mode, new EvalModels.Model("m", EvalBudgetTest.FIVE_CENTS)),
            mode,
            "all",
            trials,
            0.2,
            new BigDecimal(cap),
            Instant.EPOCH);
    String missing = "jdbc:h2:file:" + dir.resolve("no-app/anna");
    budget = new EvalBudget(new BigDecimal(cap), EvalBudgetTest.FIVE_CENTS, "m");
    return new EvalRun(
        new EvalRun.Steps(turns, this::pass, judge),
        budget,
        new RunPlan(header, List.of(selected), rubrics, dir, missing));
  }

  /** Judges on model m, one transcript rubric on core unless a test set others. */
  private void judgedBy(EvalRun.Judge answers) {
    if (rubrics.isEmpty()) {
      rubrics = List.of(ScenarioFixtures.rubric("J-ALL", Rubric.Scope.TRANSCRIPT, "core"));
    }
    judge = answers;
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
