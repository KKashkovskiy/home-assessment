package com.enrola.anna.eval;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * One trial's Markdown page: the setup, then a turn's transcript and every check, or a story's
 * persona and its transcript turn by turn, each turn with its checks, tools and cost, then the end
 * state and why it stopped; and where the conversation is.
 */
final class ScenarioPage {

  private ScenarioPage() {}

  /**
   * {@code <id>.md} for the first trial, {@code <id>.<k>.md} after it; a {@code #} would read as a
   * link's fragment.
   */
  static String fileName(Trial trial) {
    return trial.trialNo() == 1
        ? trial.scenarioId() + ".md"
        : trial.scenarioId() + "." + trial.trialNo() + ".md";
  }

  static String render(Scenario scenario, Trial trial) {
    return """
        # %s: %s

        Result: **%s**. Cost $%s, %d model calls, %d ms.

        """
            .formatted(
                trial.label(),
                scenario.title(),
                trial.status(),
                ReportWriter.usd(trial.costUsd()),
                trial.modelCalls(),
                trial.durationMs())
        + setup(scenario)
        + switch (scenario) {
          case Scenario.Turn turn -> turnBody(turn, trial);
          case Scenario.Story story -> persona(story) + storyBody(trial);
        }
        + (trial.conversationId() == null
            ? ""
            : "%nConversation `%s`, turn %d, in this run's database (`db.mv.db`).%n"
                .formatted(trial.conversationId(), trial.lastTurn()));
  }

  /** A turn trial always carries a {@link Trial.TurnDetail}. */
  private static String turnBody(Scenario.Turn turn, Trial trial) {
    Trial.TurnDetail detail = (Trial.TurnDetail) trial.detail();
    return transcript(turn, detail.reply())
        + checks("Checks", trial.checks())
        + toolCalls(detail.tools());
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

  private static String transcript(Scenario.Turn turn, String reply) {
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
        .append(reply == null ? "_no reply_" : reply)
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

  /**
   * The transcript turn by turn, the checks about no one turn, and the stop reason. A story trial
   * always carries a {@link Trial.StoryDetail}.
   */
  private static String storyBody(Trial trial) {
    Trial.StoryDetail detail = (Trial.StoryDetail) trial.detail();
    String turns =
        detail.transcript().stream()
            .map(turn -> storyTurn(turn, trial.checks()))
            .collect(Collectors.joining());
    List<Check> endState = trial.checks().stream().filter(check -> check.turn() == null).toList();
    return "## Transcript\n"
        + turns
        + "\n"
        + checks("End state", endState)
        + (detail.stopReason() == null ? "" : "\nStopped: " + detail.stopReason() + "\n");
  }

  /** Turn 0 is the opener; every other turn has the lead's line, replies, checks, tools, cost. */
  private static String storyTurn(StoryTurn turn, List<Check> checks) {
    if (turn.lead() == null) {
      return "\n" + replies(turn.replies(), "Anna (opener)");
    }
    List<Check> about =
        checks.stream().filter(check -> Objects.equals(check.turn(), turn.turnNo())).toList();
    return """

        ### Turn %d%s

        - **Lead:** %s
        %s%s- Checks: %s
        - Cost: $%s
        """
        .formatted(
            turn.turnNo(),
            turn.probe() ? " (probe after the end)" : "",
            turn.lead(),
            turn.replies().isEmpty() ? "- **Anna:** _no reply_\n" : replies(turn.replies(), "Anna"),
            turn.tools().stream()
                .map(call -> "- Tool: " + code(call) + "\n")
                .collect(Collectors.joining()),
            checkSummary(about),
            ReportWriter.usd(turn.costUsd()));
  }

  private static String replies(List<String> replies, String who) {
    return replies.stream()
        .map(reply -> "- **%s:** %s _(%d chars)_%n".formatted(who, reply, reply.length()))
        .collect(Collectors.joining());
  }

  /** {@code 11 passed}, then each failure with its evidence. */
  private static String checkSummary(List<Check> checks) {
    List<Check> failed = checks.stream().filter(check -> !check.pass()).toList();
    return (checks.size() - failed.size())
        + " passed"
        + failed.stream()
            .map(check -> "; FAIL " + check.name() + ": " + check.evidence())
            .collect(Collectors.joining());
  }

  private static String checks(String heading, List<Check> checks) {
    return "## "
        + heading
        + "\n\n| Check | Gate | Result | Evidence |\n|---|---|---|---|\n"
        + checks.stream()
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

  private static String toolCalls(List<String> tools) {
    if (tools.isEmpty()) {
      return "";
    }
    return "\n## Tool calls\n\n"
        + tools.stream().map(call -> "- " + code(call) + "\n").collect(Collectors.joining());
  }

  private static String code(String text) {
    return "`" + text.replace('`', '\'') + "`";
  }
}
