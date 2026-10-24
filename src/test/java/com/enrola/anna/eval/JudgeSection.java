package com.enrola.anna.eval;

import com.enrola.anna.eval.JudgeResult.Verdict;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The judges' part of the reports, formatting what {@link RunSummary} counts: the header's line,
 * the dry run's line, a scenario's cell, and the section with each rubric's answers by verdict and
 * every FAIL with its evidence. Nothing here reads the gates; the section says it does not change
 * them.
 */
final class JudgeSection {

  private JudgeSection() {}

  /**
   * {@code advisory, model gpt-5.1 (effort low): 4 asked, 2 code-written, 1 skipped at the cap,
   * $0.0077}, a count left out when zero, or why there is no judge.
   */
  static String line(JudgeMode judges, RunSummary summary) {
    return switch (judges) {
      case JudgeMode.Asked asked ->
          "%s: %d asked%s%s, $%s"
              .formatted(
                  advisory(asked.model()),
                  summary.judgesAsked(),
                  unlessZero(summary.judgesCodeWritten(), "code-written"),
                  unlessZero(summary.judgesSkippedAtCap(), "skipped at the cap"),
                  ReportWriter.usd(summary.judgeCost()));
      case JudgeMode.Estimated estimated -> advisory(estimated.model());
      case JudgeMode.None none -> "none (" + none.why() + ")";
    };
  }

  private static String unlessZero(long count, String what) {
    return count > 0 ? ", " + count + " " + what : "";
  }

  /** The dry run's line: at most {@code questions}, costing {@code estimate}, or why none. */
  static String planned(JudgeMode judges, long questions, BigDecimal estimate) {
    return switch (judges) {
      case JudgeMode.Estimated estimated ->
          "%s: at most %d questions, $%s, asked only while the cap has room"
              .formatted(advisory(estimated.model()), questions, ReportWriter.usd(estimate));
      case JudgeMode.Asked asked -> advisory(asked.model());
      case JudgeMode.None none -> "none (" + none.why() + ")";
    };
  }

  /** {@code 9 PASS, 1 FAIL}: a scenario's answers by verdict; empty when none was asked. */
  static String cell(List<Trial> trials) {
    return RunSummary.verdictCounts(
            trials.stream().flatMap(trial -> trial.judges().stream()).toList())
        .entrySet()
        .stream()
        .filter(count -> count.getValue() > 0)
        .map(count -> count.getValue() + " " + count.getKey())
        .collect(Collectors.joining(", "));
  }

  /** The section, or nothing when no judge was asked. */
  static String render(JudgeMode judges, RunSummary summary) {
    if (!(judges instanceof JudgeMode.Asked)) {
      return "";
    }
    return """

        ## Judges (advisory, never gating)

        These answers do not affect the gates or the exit code.
        """
        + (summary.judgeResults().isEmpty()
            ? ""
            : rubricTable(summary.judgeCounts()) + failures(summary.judgeResults()));
  }

  private static String advisory(String model) {
    return "advisory, model " + model + " (effort " + JudgeClient.EFFORT + ")";
  }

  private static String rubricTable(Map<String, Map<Verdict, Long>> counts) {
    return """

        | Rubric | PASS | FAIL | NOT_APPLICABLE | UNKNOWN | SKIPPED_BUDGET |
        |---|---|---|---|---|---|
        """
        + counts.entrySet().stream()
            .map(
                rubric ->
                    "| %s | %s |%n"
                        .formatted(
                            rubric.getKey(),
                            rubric.getValue().values().stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(" | "))))
            .collect(Collectors.joining());
  }

  /** Every FAIL with its quote and the judge's reason, for a reader to check by eye. */
  private static String failures(List<JudgeResult> answers) {
    List<JudgeResult> failed =
        answers.stream().filter(answer -> answer.verdict() == Verdict.FAIL).toList();
    if (failed.isEmpty()) {
      return "\nFailures with evidence: none.\n";
    }
    return "\nFailures with evidence\n\n"
        + failed.stream()
            .map(
                answer ->
                    "- %s#%d, %s%s: FAIL. \"%s\" Reason: %s%n"
                        .formatted(
                            answer.scenarioId(),
                            answer.trialNo(),
                            answer.rubricId(),
                            answer.turnNo() == null ? "" : ", turn " + answer.turnNo(),
                            answer.evidence(),
                            answer.note()))
            .collect(Collectors.joining());
  }
}
