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
 * Writes a run's files under {@code evals/runs/<id>/}: {@code report.md} (header, gates, coverage
 * by tag, one row per scenario, cost, notes), one {@link ScenarioPage} per trial, and {@code
 * trials.jsonl}. Markdown because a reviewer reads it on GitHub; JSONL so later columns do not
 * change the shape of a line.
 */
final class ReportWriter {

  private static final JsonMapper JSON =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

  /** One {@code trials.jsonl} line; each check carries its gate and its turn. */
  private record Line(
      String run,
      String scenario,
      String kind,
      TrialStatus status,
      List<Check> checks,
      List<String> judges,
      BigDecimal costUsd,
      int modelCalls,
      String conversationId,
      int turnNo,
      long durationMs) {}

  /** Scenarios and passes under one tag. */
  private record TagCount(int scenarios, int passed) {

    TagCount plus(TagCount other) {
      return new TagCount(scenarios + other.scenarios, passed + other.passed);
    }
  }

  private ReportWriter() {}

  /** Every file of a finished run; {@code notes} close the report. */
  static void write(RunPlan plan, RunSummary summary, List<String> notes) {
    Map<String, Scenario> byId =
        plan.selected().stream().collect(Collectors.toMap(Scenario::id, Function.identity()));
    writeFile(plan.dir().resolve("report.md"), report(plan, summary, notes));
    writeFile(
        plan.dir().resolve("trials.jsonl"),
        summary.trials().stream()
            .map(trial -> JSON.writeValueAsString(line(plan.header(), trial)) + "\n")
            .collect(Collectors.joining()));
    for (Trial trial : summary.trials()) {
      writeFile(
          plan.dir().resolve(trial.scenarioId() + ".md"),
          ScenarioPage.render(byId.get(trial.scenarioId()), trial));
    }
  }

  /** A dry run's report: the selection and the estimate; no trial ran. */
  static void plan(RunPlan plan, BigDecimal estimate) {
    RunHeader header = plan.header();
    writeFile(
        plan.dir().resolve("report.md"),
        """
        # Eval run %s (dry run)

        Estimate: $%s of $%s cap for %d scenarios (%s). No model call, no eval_run row.

        %s
        """
            .formatted(
                header.runId(),
                usd(estimate),
                cents(header.cap()),
                plan.selected().size(),
                header.selection(),
                plan.selected().stream()
                    .map(scenario -> "- " + scenario.id())
                    .collect(Collectors.joining("\n"))));
  }

  static String report(RunPlan plan, RunSummary summary, List<String> notes) {
    return headerSection(plan, summary)
        + gatesSection(summary)
        + coverageSection(plan.selected(), summary.trials())
        + scenariosSection(summary.trials())
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
        | Scenarios | %d selected (%s), %d run, %d skipped, %d crashed |
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
                ? "yes (answers from each scenario's expect block: proves the harness, not the prompt)"
                : "no",
            summary.selected(),
            header.selection(),
            summary.ran(),
            summary.skipped(),
            summary.crashed(),
            usd(summary.cost()),
            cents(header.cap()),
            summary.verdict());
  }

  /** The hard gate: every HARD check on every trial, each failure listed. */
  private static String gatesSection(RunSummary summary) {
    List<String> failures =
        summary.trials().stream()
            .flatMap(
                trial ->
                    trial.checks().stream()
                        .filter(check -> check.gate() == Check.Gate.HARD && !check.pass())
                        .map(
                            check ->
                                "- %s: %s, %s%n"
                                    .formatted(trial.scenarioId(), check.name(), check.evidence())))
            .toList();
    return """

        ## Gates
        hard: %s
        """
            .formatted(hardGate(summary, failures.size()))
        + String.join("", failures);
  }

  private static String hardGate(RunSummary summary, int failures) {
    if (summary.ran() == 0) {
      return "SKIPPED   (no trial ran)";
    }
    return failures == 0
        ? "PASS   (every hard check on every reply passed)"
        : "FAIL   (failed: %d)".formatted(failures);
  }

  private static String coverageSection(List<Scenario> selected, List<Trial> trials) {
    Map<String, Boolean> passed =
        trials.stream().collect(Collectors.toMap(Trial::scenarioId, Trial::passed));
    Map<String, TagCount> byTag = new TreeMap<>();
    for (Scenario scenario : selected) {
      var one = new TagCount(1, passed.getOrDefault(scenario.id(), false) ? 1 : 0);
      scenario.tags().forEach(tag -> byTag.merge(tag, one, TagCount::plus));
    }
    return """

        ## Coverage by tag
        | Tag | Scenarios | Pass |
        |---|---|---|
        """
        + byTag.entrySet().stream()
            .map(
                tag ->
                    "| %s | %d | %d |%n"
                        .formatted(
                            tag.getKey(), tag.getValue().scenarios(), tag.getValue().passed()))
            .collect(Collectors.joining());
  }

  private static String scenariosSection(List<Trial> trials) {
    return """

        ## Scenarios
        | Scenario | Result | Failed checks | Cost | Model calls |
        |---|---|---|---|---|
        """
        + trials.stream()
            .map(
                trial ->
                    "| [%s](%s.md) | %s | %s | $%s | %d |%n"
                        .formatted(
                            trial.scenarioId(),
                            trial.scenarioId(),
                            trial.status(),
                            cell(String.join(", ", trial.failedNames())),
                            usd(trial.costUsd()),
                            trial.modelCalls()))
            .collect(Collectors.joining());
  }

  /** A keyed run's boot also makes the product's model check, one small call not counted here. */
  private static String costSection(RunHeader header, RunSummary summary) {
    String check =
        header.mode() instanceof RunMode.Keyed
            ? " The model check at boot, one small call, is not counted."
            : "";
    return """

        ## Cost
        Total $%s. Per scenario: see table. Judges: none.%s
        """
        .formatted(usd(summary.cost()), check);
  }

  private static Line line(RunHeader header, Trial trial) {
    return new Line(
        header.runId(),
        trial.scenarioId(),
        trial.kind(),
        trial.status(),
        trial.checks(),
        trial.judges(),
        trial.costUsd(),
        trial.modelCalls(),
        trial.conversationId(),
        trial.turnNo(),
        trial.durationMs());
  }

  /** Dollars to four places, as a model call's cost shows. */
  static String usd(BigDecimal amount) {
    return amount.setScale(4, RoundingMode.HALF_UP).toPlainString();
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
