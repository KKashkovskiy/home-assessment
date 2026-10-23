package com.enrola.anna.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * One run of the suite. {@link #begin} decides by the run's mode what runs: nothing for a dry run
 * or without a key, everything offline, and with a key only an estimate within the cap. Each {@link
 * #trial} runs one scenario under the budget and prints the progress line the Evals page parses;
 * {@link #finish} writes the plan, or the report files and the {@code eval_run} row. The CLI entry
 * point and the offline test both run through it.
 */
final class EvalRun {

  private final Function<Scenario.Turn, Trial> turns;
  private final EvalBudget budget;
  private final RunPlan plan;
  private final List<Trial> trials = new ArrayList<>();
  private final List<String> notes = new ArrayList<>();

  /** {@code turns} runs one turn scenario; the {@link TurnRunner} in a run, a fake in a test. */
  EvalRun(Function<Scenario.Turn, Trial> turns, EvalBudget budget, RunPlan plan) {
    this.turns = turns;
    this.budget = budget;
    this.plan = plan;
  }

  List<Trial> trials() {
    return List.copyOf(trials);
  }

  /**
   * The scenarios to run, in order. Without a key every one is SKIPPED_NO_KEY now. A keyed run
   * whose estimate is over the cap is refused before any call: every scenario SKIPPED_BUDGET, the
   * report written, then an {@link IllegalStateException}.
   */
  List<Scenario> begin() {
    return switch (plan.header().mode()) {
      case RunMode.DryRun _ -> List.of();
      case RunMode.NoKey _ -> {
        skipAll(TrialStatus.SKIPPED_NO_KEY);
        notes.add("No key: set EVAL_OPENAI_API_KEY, or run with --offline.");
        yield List.of();
      }
      case RunMode.Offline _ -> plan.selected();
      case RunMode.Keyed _ -> {
        refuseOverCap();
        yield plan.selected();
      }
    };
  }

  /**
   * Runs one scenario, or skips it: SKIPPED_BUDGET once one more typical call would pass the cap,
   * SKIPPED_KIND for a story, which no runner takes yet.
   */
  Trial trial(Scenario scenario) {
    Trial trial =
        switch (scenario) {
          case Scenario.Turn turn ->
              budget.allowsAnotherCall()
                  ? turns.apply(turn)
                  : Trial.skipped(turn, TrialStatus.SKIPPED_BUDGET);
          case Scenario.Story story -> Trial.skipped(story, TrialStatus.SKIPPED_KIND);
        };
    budget.spend(trial.costUsd());
    add(trial);
    return trial;
  }

  /** A dry run's plan; otherwise the row, then the report, which says when the row failed. */
  void finish() {
    switch (plan.header().mode()) {
      case RunMode.DryRun _ -> writePlan();
      case RunMode.Offline _, RunMode.Keyed _, RunMode.NoKey _ -> writeReport();
    }
  }

  private void writePlan() {
    var estimate = budget.estimate(plan.selected());
    ReportWriter.plan(plan, estimate);
    System.out.printf(
        "[eval] dry run: %d scenarios, estimate $%s of $%s cap%n",
        plan.selected().size(), ReportWriter.usd(estimate), ReportWriter.cents(budget.cap()));
  }

  private void writeReport() {
    var summary = new RunSummary(trials, plan.selected().size());
    EvalRunRow.record(plan, summary).ifPresent(notes::add);
    ReportWriter.write(plan, summary, notes);
  }

  private void refuseOverCap() {
    var estimate = budget.estimate(plan.selected());
    if (budget.allows(estimate)) {
      return;
    }
    String refusal =
        "Refused: the estimate $%s is over the $%s cap; raise --budget or narrow the selection."
            .formatted(ReportWriter.usd(estimate), ReportWriter.cents(budget.cap()));
    skipAll(TrialStatus.SKIPPED_BUDGET);
    notes.add(refusal);
    finish();
    throw new IllegalStateException(refusal);
  }

  private void skipAll(TrialStatus reason) {
    plan.selected().forEach(scenario -> add(Trial.skipped(scenario, reason)));
  }

  /** Keeps the trial and prints {@code [eval] i/n <id> <status>}, the line the Evals page reads. */
  private void add(Trial trial) {
    trials.add(trial);
    System.out.printf(
        "[eval] %d/%d %s %s%n",
        trials.size(), plan.selected().size(), trial.scenarioId(), trial.status());
  }
}
