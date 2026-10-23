package com.enrola.anna.eval;

import java.math.BigDecimal;
import java.util.List;

/**
 * One trial of one scenario, {@code trialNo} counting from 1 among its scenario's trials: its
 * status, every check with its evidence, the advisory judge answers, what it cost without them,
 * where its conversation is, and what the kind's page shows, in {@code detail}.
 */
record Trial(
    String scenarioId,
    int trialNo,
    TrialStatus status,
    List<Check> checks,
    List<JudgeResult> judges,
    BigDecimal costUsd,
    int modelCalls,
    String conversationId,
    Detail detail,
    long durationMs) {

  /** What a trial of each kind leaves for its page. */
  sealed interface Detail permits TurnDetail, StoryDetail {}

  /**
   * The turn under test: its number, the reply it sent or null, and each TOOL_CALL trace event of
   * it as its name and payload JSON.
   */
  record TurnDetail(int turnNo, String reply, List<String> tools) implements Detail {
    static final TurnDetail NONE = new TurnDetail(0, null, List.of());
  }

  /** The story turn by turn, opener first, and why it stopped; null before it started. */
  record StoryDetail(List<StoryTurn> transcript, String stopReason) implements Detail {
    static final StoryDetail NONE = new StoryDetail(List.of(), null);
  }

  /** {@code turn} or {@code story}, as the YAML spells it. */
  String kind() {
    return switch (detail) {
      case TurnDetail _ -> "turn";
      case StoryDetail _ -> "story";
    };
  }

  /** The turns after the opener that sent a reply: the turns a judge can mark. */
  List<Integer> repliedTurns() {
    return switch (detail) {
      case TurnDetail turn -> turn.reply() == null ? List.of() : List.of(turn.turnNo());
      case StoryDetail story ->
          story.transcript().stream()
              .filter(turn -> turn.turnNo() > 0 && !turn.replies().isEmpty())
              .map(StoryTurn::turnNo)
              .toList();
    };
  }

  /** The last turn the trial ran; 0 when it ran none. */
  int lastTurn() {
    return switch (detail) {
      case TurnDetail turn -> turn.turnNo();
      case StoryDetail story ->
          story.transcript().isEmpty() ? 0 : story.transcript().getLast().turnNo();
    };
  }

  /** Why a story stopped; null for a turn, or a story that never started. */
  String stopReason() {
    return switch (detail) {
      case TurnDetail _ -> null;
      case StoryDetail story -> story.stopReason();
    };
  }

  boolean passed() {
    return status == TrialStatus.PASS;
  }

  /** {@code story-troll#2}: the trial in a gate's failure lines. */
  String label() {
    return scenarioId + "#" + trialNo;
  }

  /** The names of the failed checks, for the report table and the JUnit abort message. */
  List<String> failedNames() {
    return checks.stream().filter(check -> !check.pass()).map(Check::name).toList();
  }

  /** What its judges cost, a line apart from {@link #costUsd()}. */
  BigDecimal judgeCostUsd() {
    return judges.stream().map(JudgeResult::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** This trial with {@code answers} as its judges; nothing else changes, its status least. */
  Trial withJudges(List<JudgeResult> answers) {
    return new Trial(
        scenarioId,
        trialNo,
        status,
        checks,
        List.copyOf(answers),
        costUsd,
        modelCalls,
        conversationId,
        detail,
        durationMs);
  }

  /** A trial that did not run, for the reason {@code status} names. */
  static Trial skipped(Scenario scenario, int trialNo, TrialStatus status) {
    return notRun(scenario, trialNo, status, List.of());
  }

  /** A scenario the product refused before its turn; {@code refusal} says how. */
  static Trial refused(Scenario scenario, int trialNo, Check refusal) {
    return notRun(scenario, trialNo, TrialStatus.FAIL, List.of(refusal));
  }

  /**
   * A scenario the harness could not run, such as a label the calendar cannot produce: a FAIL on
   * the hard gate, since a broken scenario must fail the run, not pass it unproved.
   */
  static Trial scenarioFault(Scenario scenario, int trialNo, String reason) {
    return notRun(
        scenario, trialNo, TrialStatus.FAIL, List.of(Check.hard("scenario", false, reason)));
  }

  private static Trial notRun(
      Scenario scenario, int trialNo, TrialStatus status, List<Check> checks) {
    return new Trial(
        scenario.id(),
        trialNo,
        status,
        checks,
        List.of(),
        BigDecimal.ZERO,
        0,
        null,
        switch (scenario) {
          case Scenario.Turn _ -> TurnDetail.NONE;
          case Scenario.Story _ -> StoryDetail.NONE;
        },
        0);
  }
}
