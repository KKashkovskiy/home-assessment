package com.enrola.anna.eval;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * One trial's Markdown page: the setup, a turn's transcript or a story's persona, every check with
 * its gate and evidence, the tool calls, and where the conversation is.
 */
final class ScenarioPage {

  private ScenarioPage() {}

  static String render(Scenario scenario, Trial trial) {
    return """
        # %s: %s

        Result: **%s**. Cost $%s, %d model calls, %d ms.

        """
            .formatted(
                trial.scenarioId(),
                scenario.title(),
                trial.status(),
                ReportWriter.usd(trial.costUsd()),
                trial.modelCalls(),
                trial.durationMs())
        + setup(scenario)
        + switch (scenario) {
          case Scenario.Turn turn -> transcript(turn, trial);
          case Scenario.Story story -> persona(story);
        }
        + checks(trial)
        + toolCalls(trial)
        + (trial.conversationId() == null
            ? ""
            : "%nConversation `%s`, turn %d, in this run's database (`db.mv.db`).%n"
                .formatted(trial.conversationId(), trial.turnNo()));
  }

  private static String setup(Scenario scenario) {
    String lead =
        scenario.lead().name() != null
            ? scenario.lead().name()
            : scenario.lead().inline().givenName() + " (inline)";
    return """
        | | |
        |---|---|
        | Lead | %s |
        | Clock | %s |
        | Calendar | %s |
        | Tags | %s |

        """
        .formatted(
            lead,
            scenario.clock(),
            scenario.calendar().value(),
            String.join(", ", scenario.tags()));
  }

  private static String transcript(Scenario.Turn turn, Trial trial) {
    var lines = new StringBuilder("## Transcript\n\n");
    if (!turn.seed().equals(Scenario.Seed.NONE)) {
      lines.append("Seed: ").append(turn.seed()).append("\n\n");
    }
    for (Scenario.History line : turn.history()) {
      lines.append(
          line.agent() != null ? "- **Anna:** " + line.agent() : "- **Lead:** " + line.lead());
      if (line.offeredSlots() != null) {
        lines.append(" _(offered: ").append(String.join("; ", line.offeredSlots())).append(")_");
      }
      lines.append('\n');
    }
    return lines
        .append("- **Lead (under test):** ")
        .append(turn.message())
        .append("\n- **Anna (reply):** ")
        .append(trial.reply() == null ? "_no reply_" : trial.reply())
        .append("\n\n")
        .toString();
  }

  private static String persona(Scenario.Story story) {
    return """
        ## Persona

        %s

        Stops when: %s

        """
        .formatted(story.persona().strip(), String.join("; ", story.stopWhen()));
  }

  private static String checks(Trial trial) {
    return "## Checks\n\n| Check | Gate | Result | Evidence |\n|---|---|---|---|\n"
        + trial.checks().stream()
            .map(
                check ->
                    "| %s | %s | %s | %s |%n"
                        .formatted(
                            ReportWriter.cell(check.name()),
                            check.gate().name().toLowerCase(Locale.ROOT),
                            check.pass() ? "pass" : "FAIL",
                            ReportWriter.cell(check.evidence())))
            .collect(Collectors.joining());
  }

  private static String toolCalls(Trial trial) {
    if (trial.tools().isEmpty()) {
      return "";
    }
    return "\n## Tool calls\n\n"
        + trial.tools().stream()
            .map(call -> "- `" + call.replace('`', '\'') + "`\n")
            .collect(Collectors.joining());
  }
}
