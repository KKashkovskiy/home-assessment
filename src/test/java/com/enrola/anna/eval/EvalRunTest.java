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
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run's branches with a fake turn runner at $0.04 a trial and $0.05 a typical call: what each
 * mode runs, the cap before and during the run, and a trial that crashes. The app database does not
 * exist here, so no row is written and the report says so.
 */
class EvalRunTest {

  static final Scenario.Turn FIRST =
      ScenarioFixtures.turn("turn-first", "hi", Scenario.Expect.NONE);
  static final Scenario.Turn SECOND =
      ScenarioFixtures.turn("turn-second", "hi", Scenario.Expect.NONE);
  static final Scenario.Turn THIRD =
      ScenarioFixtures.turn("turn-third", "hi", Scenario.Expect.NONE);
  static final Scenario.Story STORY = ScenarioFixtures.story("story-lauren-books");

  @TempDir Path dir;

  final List<String> ran = new ArrayList<>();

  @Test
  void dryRunPlansAndRunsNothing() throws IOException {
    EvalRun run = run(new RunMode.DryRun(), "10", this::pass, FIRST, SECOND, STORY);

    assertThat(run.begin()).isEmpty();
    run.finish();

    assertThat(Files.readString(dir.resolve("report.md")))
        .contains("(dry run)", "Estimate: $0.2000 of $10.00 cap for 3 scenarios");
    assertThat(run.trials()).isEmpty();
  }

  @Test
  void withoutAKeyEveryTrialIsSkippedAndTheReportWritten() throws IOException {
    EvalRun run = run(new RunMode.NoKey(), "10", this::pass, FIRST, STORY);

    assertThat(run.begin()).isEmpty();
    run.finish();

    assertThat(run.trials())
        .extracting(Trial::status)
        .containsOnly(TrialStatus.SKIPPED_NO_KEY)
        .hasSize(2);
    assertThat(Files.readString(dir.resolve("report.md")))
        .contains("| Verdict | SKIPPED |", "hard: SKIPPED", "No key", "No eval_run row");
  }

  /** Two turns are estimated at $0.20, over a $0.10 cap: nothing runs, and the report says why. */
  @Test
  void keyedRunOverTheCapIsRefusedBeforeAnyCall() throws IOException {
    EvalRun run = run(new RunMode.Keyed("eval"), "0.10", this::pass, FIRST, SECOND);

    assertThatThrownBy(run::begin)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("over the $0.10 cap");
    assertThat(ran).isEmpty();
    assertThat(run.trials()).extracting(Trial::status).containsOnly(TrialStatus.SKIPPED_BUDGET);
    assertThat(Files.readString(dir.resolve("report.md")))
        .contains("Refused: the estimate $0.2000");
  }

  @Test
  void capBelowOneCallSkipsTurnsAndStoriesAreSkippedByKind() {
    EvalRun run = run(new RunMode.Offline(), "0.01", this::pass, FIRST, STORY);

    run.begin().forEach(run::trial);

    assertThat(run.trials())
        .extracting(Trial::status)
        .containsExactly(TrialStatus.SKIPPED_BUDGET, TrialStatus.SKIPPED_KIND);
    assertThat(ran).isEmpty();
  }

  /** At $0.08 spent of $0.10, one more $0.05 call would pass the cap, so the third skips. */
  @Test
  void capReachedMidRunSkipsTheRest() {
    EvalRun run = run(new RunMode.Offline(), "0.10", this::pass, FIRST, SECOND, THIRD);

    run.begin().forEach(run::trial);

    assertThat(run.trials())
        .extracting(Trial::status)
        .containsExactly(TrialStatus.PASS, TrialStatus.PASS, TrialStatus.SKIPPED_BUDGET);
    assertThat(ran).containsExactly("turn-first", "turn-second");
  }

  /** A trial that throws is never recorded, and the run fails though the rest passed. */
  @Test
  void aCrashedTrialFailsTheRun() throws IOException {
    EvalRun run =
        run(
            new RunMode.Offline(),
            "10",
            turn -> {
              if (turn.equals(SECOND)) {
                throw new IllegalStateException("boom");
              }
              return pass(turn);
            },
            FIRST,
            SECOND);

    List<Scenario> selected = run.begin();
    run.trial(selected.getFirst());
    assertThatThrownBy(() -> run.trial(selected.get(1))).hasMessage("boom");
    run.finish();

    assertThat(Files.readString(dir.resolve("report.md")))
        .contains("1 run, 0 skipped, 1 crashed", "| Verdict | FAIL |");
  }

  private EvalRun run(
      RunMode mode, String cap, Function<Scenario.Turn, Trial> turns, Scenario... selected) {
    var header =
        new RunHeader("test", "v1", "m", "low", mode, "all", new BigDecimal(cap), Instant.EPOCH);
    String missing = "jdbc:h2:file:" + dir.resolve("no-app/anna");
    return new EvalRun(
        turns,
        new EvalBudget(new BigDecimal(cap), EvalBudgetTest.FIVE_CENTS, "m"),
        new RunPlan(header, List.of(selected), dir, missing));
  }

  private Trial pass(Scenario.Turn turn) {
    ran.add(turn.id());
    return new Trial(
        turn.id(),
        "turn",
        TrialStatus.PASS,
        List.of(),
        List.of(),
        new BigDecimal("0.04"),
        1,
        "c-" + turn.id(),
        1,
        "Ok?",
        List.of(),
        10);
  }
}
