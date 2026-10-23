package com.enrola.anna.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a run's files under {@code evals/runs/<id>/}: {@code report.md} (header with the
 * statistics, the three gates, coverage by tag, one row per scenario with its trials, the advisory
 * {@link JudgeSection}, cost, notes), one {@link ScenarioPage} per trial, {@code trials.jsonl}, and
 * {@code exit-code}, the gates' code that {@code run.sh} exits with. Markdown because a reviewer
 * reads it on GitHub; JSONL so later columns do not change the shape of a line. The numbers come
 * from {@link RunSummary} and {@link Gates}; nothing is computed here.
 */
final class ReportWriter {

  private static final JsonMapper JSON =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

  /** The file whose one line is the gates' exit code. */
  static final String EXIT_CODE = "exit-code";

  /**
   * One {@code trials.jsonl} line; each check carries its gate and its turn, each judge its own.
   */
  private record Line(
      String run,
      String scenario,
      int trialNo,
      String kind,
      TrialStatus status,
      List<Check> checks,
      List<JudgeResult> judges,
      BigDecimal costUsd,
      BigDecimal judgeCostUsd,
      int modelCalls,
      String conversationId,
      int turnNo,
      String stopReason,
      long durationMs) {}

  /** Scenarios and clean ones under one tag. */
  private record TagCount(int scenarios, int clean) {

    TagCount plus(TagCount other) {
      return new TagCount(scenarios + other.scenarios, clean + other.clean);
    }
  }

  private ReportWriter() {}

  /** Every file of a finished run; {@code notes} close the report. */
  static void write(RunPlan plan, RunSummary summary, List<String> notes) {
    Map<String, Scenario> byId =
        plan.selected().stream().collect(Collectors.toMap(Scenario::id, Function.identity()));
    writeFile(plan.dir().resolve("report.md"), report(plan, summary, notes));
    writeFile(plan.dir().resolve(EXIT_CODE), summary.gates().exitCode() + "\n");
    writeFile(
        plan.dir().resolve("trials.jsonl"),
        summary.trials().stream()
            .map(trial -> JSON.writeValueAsString(line(plan.header(), trial)) + "\n")
            .collect(Collectors.joining()));
    for (Trial trial : summary.trials()) {
      writeFile(
          plan.dir().resolve(ScenarioPage.fileName(trial)),
          ScenarioPage.render(byId.get(trial.scenarioId()), trial));
    }
  }

  /**
   * A dry run's report: the selection, the trials' estimate against the cap, and the judges' at
   * most {@code judgeCalls} questions costing {@code judgeEstimate} apart; no trial ran.
   */
  static void plan(RunPlan plan, BigDecimal estimate, long judgeCalls, BigDecimal judgeEstimate) {
    RunHeader header = plan.header();
    writeFile(
        plan.dir().resolve("report.md"),
        """
        # Eval run %s (dry run)

        Estimate: $%s of $%s cap for %d scenarios (%s) x %d trials. No model call, no eval_run row.
        Judges: %s.

        %s
        """
            .formatted(
                header.runId(),
                usd(estimate),
                cents(header.cap()),
                plan.selected().size(),
                header.selection(),
                header.trials(),
                JudgeSection.planned(header.judges(), judgeCalls, judgeEstimate),
                plan.selected().stream()
                    .map(scenario -> "- " + scenario.id())
                    .collect(Collectors.joining("\n"))));
  }

  static String report(RunPlan plan, RunSummary summary, List<String> notes) {
    return headerSection(plan, summary)
        + gatesSection(summary)
        + coverageSection(plan.selected(), summary)
        + scenariosSection(plan.header(), summary)
        + JudgeSection.render(plan.header().judges(), summary)
        + costSection(plan.header(), summary)
        + notes.stream().map(note -> "\n" + note + "\n").collect(Collectors.joining());
  }

  private static String headerSection(RunPlan plan, RunSummary summary) {
    RunHeader header = plan.header();
    return """
        # Eval run %s

        | | |
        |---|---|
        | Prompt version | %s |
        | Model | %s (effort %s) |
        | Key | %s |
        | Offline stand-in | %s |
        | Clock | per scenario |
        | Scenarios | %d selected (%s) |
        | Trials per scenario | %d |
        | Judges | %s |
        | Trials | %d run, %d skipped, %d crashed |
        | Pass^k | %s |
        | Per-trial pass rate | %s |
        | Cost | $%s of $%s cap |
        | Verdict | %s |
        """
        .formatted(
            header.runId(),
            header.promptVersion(),
            header.model(),
            header.effort(),
            header.keyLine(),
            header.offline()
                ? "yes (turns answer from their expect block, stories from scripted lines and"
                    + " keyword rules: proves the harness, not the prompt)"
                : "no",
            plan.selected().size(),
            header.selection(),
            header.trials(),
            JudgeSection.line(header.judges(), summary),
            summary.ran(),
            summary.skipped(),
            summary.crashed(),
            passK(summary),
            passRate(summary),
            usd(summary.cost()),
            cents(header.cap()),
            summary.verdict());
  }

  /** {@code 87% (26 of 30 scenarios clean)}. */
  private static String passK(RunSummary summary) {
    if (summary.judged() == 0) {
      return "n/a (no trial judged)";
    }
    return "%s (%d of %d scenarios clean)"
        .formatted(percent(summary.passK()), summary.cleanScenarios(), summary.judgedScenarios());
  }

  /** {@code 80% (49% to 94%, n=10)}: the rate with its 95% Wilson interval. */
  private static String passRate(RunSummary summary) {
    if (summary.judged() == 0) {
      return "n/a (no trial judged)";
    }
    Stats.Interval interval = summary.passRateInterval();
    return "%s (%s to %s, n=%d)"
        .formatted(
            percent((double) summary.count(TrialStatus.PASS) / summary.judged()),
            percent(interval.low()),
            percent(interval.high()),
            summary.judged());
  }

  /**
   * The three gates, each failure listed under its gate, and the exit code they add up to. With
   * nothing run each gate says so.
   */
  private static String gatesSection(RunSummary summary) {
    if (summary.ran() == 0) {
      return """

          ## Gates
          hard: SKIPPED   (no trial ran)
          global: SKIPPED   (no trial ran)
          validity: SKIPPED   (no trial ran)
          exit code: 0
          """;
    }
    GateResult gates = summary.gates();
    return "\n## Gates\n"
        + hardGate(gates)
        + globalGate(gates)
        + validityGate(gates)
        + "exit code: "
        + gates.exitCode()
        + "\n";
  }

  private static String hardGate(GateResult gates) {
    return gate(
        "hard",
        "every hard check on every reply passed",
        gates.hardFailures().stream()
            .map(
                failure ->
                    "%s: %s, %s"
                        .formatted(
                            failure.trial().label(),
                            failure.check().name(),
                            failure.check().evidence()))
            .toList());
  }

  private static String globalGate(GateResult gates) {
    return gate(
        "global",
        "every trial of a core scenario passed",
        gates.coreFailures().stream()
            .map(trial -> trial.label() + ": " + String.join(", ", trial.failedNames()))
            .toList());
  }

  private static String validityGate(GateResult gates) {
    return "validity: %s   (%d of %d trials INVALID; the gate allows %s)%n"
        .formatted(
            gates.validity() ? "PASS" : "FAIL",
            gates.invalid(),
            gates.ran(),
            percent(gates.validityThreshold()));
  }

  /** {@code gate: PASS (why)}, or FAIL with the count and one line per failure. */
  private static String gate(String gate, String passedBecause, List<String> failures) {
    if (failures.isEmpty()) {
      return "%s: PASS   (%s)%n".formatted(gate, passedBecause);
    }
    return "%s: FAIL   (failed: %d)%n".formatted(gate, failures.size())
        + failures.stream().map(failure -> "- " + failure + "\n").collect(Collectors.joining());
  }

  /** Per tag, the scenarios and those clean: a judged trial and no FAIL. */
  private static String coverageSection(List<Scenario> selected, RunSummary summary) {
    Map<String, List<Trial>> byId =
        summary.byScenario().stream()
            .collect(Collectors.toMap(trials -> trials.getFirst().scenarioId(), trials -> trials));
    Map<String, TagCount> byTag = new TreeMap<>();
    for (Scenario scenario : selected) {
      boolean clean = Stats.clean(byId.getOrDefault(scenario.id(), List.of()));
      var one = new TagCount(1, clean ? 1 : 0);
      scenario.tags().forEach(tag -> byTag.merge(tag, one, TagCount::plus));
    }
    return """

        ## Coverage by tag
        | Tag | Scenarios | Clean |
        |---|---|---|
        """
        + byTag.entrySet().stream()
            .map(
                tag ->
                    "| %s | %d | %d |%n"
                        .formatted(
                            tag.getKey(), tag.getValue().scenarios(), tag.getValue().clean()))
            .collect(Collectors.joining());
  }

  /**
   * One row per scenario: a link per trial, the trials cell, the result, failures and cost, and
   * when judges were asked, their answers.
   */
  private static String scenariosSection(RunHeader header, RunSummary summary) {
    boolean judged = header.judges() instanceof JudgeMode.Asked;
    return """

        ## Scenarios
        | Scenario | Trials | Result | Failed checks | Cost | Model calls |%s
        |---|---|---|---|---|---|%s
        """
            .formatted(judged ? " Judges |" : "", judged ? "---|" : "")
        + summary.byScenario().stream()
            .map(
                trials ->
                    scenarioRow(trials)
                        + (judged ? " " + JudgeSection.cell(trials) + " |" : "")
                        + "\n")
            .collect(Collectors.joining());
  }

  private static String scenarioRow(List<Trial> trials) {
    String links =
        trials.stream()
            .map(
                trial ->
                    "[%s](%s)"
                        .formatted(
                            trial.trialNo() == 1 ? trial.scenarioId() : "#" + trial.trialNo(),
                            ScenarioPage.fileName(trial)))
            .collect(Collectors.joining(" "));
    String failed =
        trials.stream()
            .filter(trial -> !trial.failedNames().isEmpty())
            .map(
                trial ->
                    (trials.size() > 1 ? "#" + trial.trialNo() + ": " : "")
                        + String.join(", ", trial.failedNames()))
            .collect(Collectors.joining("; "));
    return "| %s | %s | %s | %s | $%s | %d |"
        .formatted(
            links,
            trialsCell(trials),
            result(trials),
            cell(failed),
            usd(trials.stream().map(Trial::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add)),
            trials.stream().mapToInt(Trial::modelCalls).sum());
  }

  /** {@code 2/3}: passes of judged trials, then any INVALID and skipped ones. */
  static String trialsCell(List<Trial> trials) {
    long passed = trials.stream().filter(Trial::passed).count();
    long judged = trials.stream().filter(trial -> trial.status().judged()).count();
    long invalid = trials.stream().filter(trial -> trial.status() == TrialStatus.INVALID).count();
    long skipped = trials.stream().filter(trial -> trial.status().skipped()).count();
    return passed
        + "/"
        + judged
        + (invalid > 0 ? " INVALID " + invalid : "")
        + (skipped > 0 ? " skipped " + skipped : "");
  }

  /** FAIL on any FAIL, else PASS on any PASS, else the first trial's status. */
  static String result(List<Trial> trials) {
    if (trials.stream().anyMatch(trial -> trial.status() == TrialStatus.FAIL)) {
      return TrialStatus.FAIL.name();
    }
    return trials.stream().anyMatch(Trial::passed)
        ? TrialStatus.PASS.name()
        : trials.getFirst().status().name();
  }

  /**
   * The trials' total, never the judges', whose cost the header gives; a keyed run's boot also
   * makes the product's model check, one small call not counted here.
   */
  private static String costSection(RunHeader header, RunSummary summary) {
    String check =
        header.mode() instanceof RunMode.Keyed
            ? " The model check at boot, one small call, is not counted."
            : "";
    return """

        ## Cost
        Total $%s. Per scenario: see table; a story's includes its simulated lead, never the judges.%s
        """
        .formatted(usd(summary.cost()), check);
  }

  private static Line line(RunHeader header, Trial trial) {
    return new Line(
        header.runId(),
        trial.scenarioId(),
        trial.trialNo(),
        trial.kind(),
        trial.status(),
        trial.checks(),
        trial.judges(),
        trial.costUsd(),
        trial.judgeCostUsd(),
        trial.modelCalls(),
        trial.conversationId(),
        trial.lastTurn(),
        trial.stopReason(),
        trial.durationMs());
  }

  /** Dollars to four places, as a model call's cost shows. */
  static String usd(BigDecimal amount) {
    return amount.setScale(4, RoundingMode.HALF_UP).toPlainString();
  }

  /** A share as a whole percentage: {@code 0.875} is {@code 88%}. */
  static String percent(double share) {
    return Math.round(share * 100) + "%";
  }

  /** Dollars to the cent, as a cap is set. */
  static String cents(BigDecimal amount) {
    return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /** A Markdown table cell: pipes escaped, one line. */
  static String cell(String text) {
    return text.replace("|", "\\|").replace("\n", " ");
  }

  private static void writeFile(Path file, String text) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, text);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }
}
