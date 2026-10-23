package com.enrola.anna.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * One run of the suite. {@link #begin} decides by the run's mode what runs: nothing for a dry run
 * or without a key, everything offline, and with a key only an estimate within the cap. Each {@link
 * #trial} runs one trial of one scenario under the budget and prints the progress line the Evals
 * page parses; {@link #finish} writes the plan, or the gates, the report files and the {@code
 * eval_run} row. The CLI entry point and the offline test both run through it.
 */
final class EvalRun {

  /** Runs trial {@code trialNo} of one scenario: a runner in a run, a fake in a test. */
  @FunctionalInterface
  interface Runner<S extends Scenario> {
    Trial run(S scenario, int trialNo);
  }

  private final Runner<Scenario.Turn> turns;
  private final Runner<Scenario.Story> stories;
  private final EvalBudget budget;
  private final RunPlan plan;
  private final List<Trial> trials = new ArrayList<>();
  private final List<String> notes = new ArrayList<>();

  /** A story's runner asks the budget before each of its lines; a turn's is asked here. */
  EvalRun(
      Runner<Scenario.Turn> turns,
      Runner<Scenario.Story> stories,
      EvalBudget budget,
      RunPlan plan) {
    this.turns = turns;
    this.stories = stories;
    this.budget = budget;
    this.plan = plan;
  }

  List<Trial> trials() {
    return List.copyOf(trials);
  }

  /**
   * The scenarios to run, in order, each for the header's trials. Without a key every trial is
   * SKIPPED_NO_KEY now. A keyed run whose estimate is over the cap is refused before any call:
   * every trial SKIPPED_BUDGET, the report written, then an {@link IllegalStateException}.
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
   * Runs trial {@code trialNo} of a scenario, or skips it: a turn is SKIPPED_BUDGET once one more
   * typical call would pass the cap.
   */
  Trial trial(Scenario scenario, int trialNo) {
    Trial trial =
        switch (scenario) {
          case Scenario.Turn turn ->
              budget.allowsAnotherCall()
                  ? turns.run(turn, trialNo)
                  : Trial.skipped(turn, trialNo, TrialStatus.SKIPPED_BUDGET);
          case Scenario.Story story -> stories.run(story, trialNo);
        };
    budget.spend(trial.costUsd());
    add(trial);
    return trial;
  }

  /**
   * A dry run's plan; otherwise the gates, the row, then the report, which says when the row
   * failed. The gates are returned for the build to fail on.
   */
  GateResult finish() {
    GateResult gates = Gates.evaluate(trials, plan.selected(), plan.header().validityThreshold());
    switch (plan.header().mode()) {
      case RunMode.DryRun _ -> writePlan();
      case RunMode.Offline _, RunMode.Keyed _, RunMode.NoKey _ -> writeReport(gates);
    }
    return gates;
  }

  private void writePlan() {
    var estimate = budget.estimate(plan.selected(), plan.header().trials());
    ReportWriter.plan(plan, estimate);
    System.out.printf(
        "[eval] dry run: %d scenarios x %d trials, estimate $%s of $%s cap%n",
        plan.selected().size(),
        plan.header().trials(),
        ReportWriter.usd(estimate),
        ReportWriter.cents(budget.cap()));
  }

  private void writeReport(GateResult gates) {
    var summary = new RunSummary(trials, expected(), gates);
    EvalRunRow.record(plan, summary).ifPresent(notes::add);
    ReportWriter.write(plan, summary, notes);
  }

  private void refuseOverCap() {
    var estimate = budget.estimate(plan.selected(), plan.header().trials());
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
    for (Scenario scenario : plan.selected()) {
      for (int trialNo = 1; trialNo <= plan.header().trials(); trialNo++) {
        add(Trial.skipped(scenario, trialNo, reason));
      }
    }
  }

  private int expected() {
    return plan.selected().size() * plan.header().trials();
  }

  /**
   * Keeps the trial and prints {@code [eval] i/n <id> <status>}, the line the Evals page reads; n
   * counts every trial of every scenario.
   */
  private void add(Trial trial) {
    trials.add(trial);
    System.out.printf(
        "[eval] %d/%d %s %s%n", trials.size(), expected(), trial.scenarioId(), trial.status());
  }
}
