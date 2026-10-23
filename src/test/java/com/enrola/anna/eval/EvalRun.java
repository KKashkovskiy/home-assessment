package com.enrola.anna.eval;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One run of the suite. {@link #begin} decides by the run's mode what runs: nothing for a dry run
 * or without a key, everything offline, and with a key only an estimate within the cap. Each {@link
 * #trial} runs one trial of one scenario under the budget and prints the progress line the Evals
 * page parses; {@link #finish} writes the plan, or the gates, then asks the judges while the cap
 * has room, then writes the report files and the {@code eval_run} row. The CLI entry point and the
 * offline test both run through it.
 */
final class EvalRun {

  /** Runs trial {@code trialNo} of one scenario: a runner in a run, a fake in a test. */
  @FunctionalInterface
  interface Runner<S extends Scenario> {
    Trial run(S scenario, int trialNo);
  }

  /** Answers one advisory question about a finished trial: the judge client, or a fake. */
  @FunctionalInterface
  interface Judge {
    JudgeResult ask(Scenario scenario, Trial trial, Judging.Question question);
  }

  /** What a run calls: a runner per kind for the trials, and the judge for their questions. */
  record Steps(Runner<Scenario.Turn> turns, Runner<Scenario.Story> stories, Judge judge) {}

  private final Steps steps;
  private final EvalBudget budget;
  private final RunPlan plan;
  private final List<Trial> trials = new ArrayList<>();
  private final List<String> notes = new ArrayList<>();

  /** A story's runner asks the budget before each of its lines; a turn's is asked here. */
  EvalRun(Steps steps, EvalBudget budget, RunPlan plan) {
    this.steps = steps;
    this.budget = budget;
    this.plan = plan;
  }

  List<Trial> trials() {
    return List.copyOf(trials);
  }

  /**
   * The scenarios to run, in order, each for the header's trials. Without a key every trial is
   * SKIPPED_NO_KEY now. A keyed run whose estimate is over the cap is refused before any call:
   * every trial SKIPPED_BUDGET, the report written, then an {@link IllegalStateException}. Judges
   * are not in that estimate: they come last, each only while one more call fits.
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
                  ? steps.turns().run(turn, trialNo)
                  : Trial.skipped(turn, trialNo, TrialStatus.SKIPPED_BUDGET);
          case Scenario.Story story -> steps.stories().run(story, trialNo);
        };
    budget.spend(trial.costUsd());
    add(trial);
    return trial;
  }

  /**
   * A dry run's plan; otherwise the gates, from the checks alone, then the judges, then the row and
   * the report, which says when the row failed. The gates are returned for the build to fail on.
   */
  GateResult finish() {
    GateResult gates = Gates.evaluate(trials, plan.selected(), plan.header().validityThreshold());
    switch (plan.header().mode()) {
      case RunMode.DryRun _ -> writePlan();
      case RunMode.Offline _, RunMode.Keyed _, RunMode.NoKey _ -> {
        judgeTrials();
        writeReport(gates);
      }
    }
    return gates;
  }

  /** The trials' estimate against the cap, and the judges' apart, since only the cap stops them. */
  private void writePlan() {
    var estimate = budget.estimate(plan.selected(), plan.header().trials());
    long judgeCalls = judgeCalls();
    String judgeModel = plan.header().judges().judgeModel();
    var judgeEstimate =
        judgeModel == null ? BigDecimal.ZERO : budget.judgeEstimate(judgeCalls, judgeModel);
    ReportWriter.plan(plan, estimate, judgeCalls, judgeEstimate);
    System.out.printf(
        "[eval] dry run: %d scenarios x %d trials, estimate $%s of $%s cap;"
            + " judges %d questions, $%s%n",
        plan.selected().size(),
        plan.header().trials(),
        ReportWriter.usd(estimate),
        ReportWriter.cents(budget.cap()),
        judgeCalls,
        ReportWriter.usd(judgeEstimate));
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

  /** The judge questions the selection may take at most; none when no judge is priced. */
  private long judgeCalls() {
    if (plan.header().judges().judgeModel() == null) {
      return 0;
    }
    return plan.selected().stream()
            .mapToLong(scenario -> Judging.calls(scenario, plan.rubrics()))
            .sum()
        * plan.header().trials();
  }

  /**
   * Every recorded trial with its judges' answers, each printed as {@code [judge] i/m <trial>
   * <rubric> <verdict>}. They are asked once all trials ran, so an advisory call never takes the
   * budget a trial needed.
   */
  private void judgeTrials() {
    if (!(plan.header().judges() instanceof JudgeMode.Asked)) {
      return;
    }
    Map<String, Scenario> byId =
        plan.selected().stream().collect(Collectors.toMap(Scenario::id, Function.identity()));
    List<List<Judging.Question>> questions =
        trials.stream()
            .map(trial -> Judging.questions(byId.get(trial.scenarioId()), trial, plan.rubrics()))
            .toList();
    int total = questions.stream().mapToInt(List::size).sum();
    int asked = 0;
    for (int index = 0; index < trials.size(); index++) {
      Trial trial = trials.get(index);
      List<JudgeResult> answers = new ArrayList<>();
      for (Judging.Question question : questions.get(index)) {
        JudgeResult answer = answer(byId.get(trial.scenarioId()), trial, question);
        answers.add(answer);
        System.out.printf(
            "[judge] %d/%d %s %s %s%n",
            ++asked, total, trial.label(), answer.rubricId(), answer.verdict());
      }
      trials.set(index, trial.withJudges(answers));
    }
  }

  /**
   * The judge's answer while one more call fits the cap. A judge that throws is UNKNOWN: an
   * advisory question must never cost the run its report, row or exit code.
   */
  private JudgeResult answer(Scenario scenario, Trial trial, Judging.Question question) {
    if (!budget.allowsAnotherJudgeCall(plan.header().judges().judgeModel())) {
      return JudgeResult.skipped(question, trial);
    }
    JudgeResult answer;
    try {
      answer = steps.judge().ask(scenario, trial, question);
    } catch (RuntimeException e) {
      answer = JudgeResult.crashed(question, trial, e);
    }
    budget.spend(answer.costUsd());
    return answer;
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
