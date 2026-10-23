package com.enrola.anna.web;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.conversation.ConversationStatus;
import com.enrola.anna.lead.Lead;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Everything the chat and state panes render for one conversation. With the PII mask on, {@code
 * lead}, the thread, {@code summary} and {@code facts} are already masked; templates read those,
 * never the conversation's own summary or extracted facts. {@code seen} holds the stages the
 * model's decisions named, {@code facts} is null before the first model call and {@code booking} is
 * null before a booking row exists.
 */
public record ConsoleView(
    Conversation conversation,
    Lead lead,
    String leadLocalTime,
    List<ThreadItem> thread,
    Set<Stage> seen,
    List<Counter> counters,
    String summary,
    Facts facts,
    BookingCard booking,
    int maxInboundChars) {

  /** One counter in the state header, against the limit code enforces. */
  public record Counter(String label, int value, int limit, boolean atLimit) {}

  /** The facts the model extracted so far; a null field is not known yet. */
  public record Facts(
      String coverageGoal,
      String coverType,
      String currentCoverNote,
      String preferredDay,
      String preferredTimeOfDay) {}

  /** The booked call as its row has it, in the lead's and the advisors' time. */
  public record BookingCard(
      String eventId, String leadTime, String advisorTime, String startUtc, int minutes) {}

  /** How a badge reads beside its yes or no: plainly, as the goal, or as something to look at. */
  public enum Tone {
    PLAIN,
    PASS,
    WARN
  }

  /** A state badge: always a yes or no in words, the tone only a hint. */
  public record Badge(String label, boolean on, Tone tone, String detail) {}

  /** Where a stage stands on the tracker. */
  public enum StepState {
    DONE,
    SKIPPED,
    CURRENT,
    AHEAD
  }

  /** One step of the stage tracker. */
  public record Step(Stage stage, StepState state) {

    public boolean current() {
      return state == StepState.CURRENT;
    }
  }

  /** The items of one turn, for the fragment the composer swaps in. */
  public List<ThreadItem> turn(int turnNo) {
    return thread.stream().filter(item -> item.turnNo() == turnNo).toList();
  }

  /**
   * Stages before the current one are done, or skipped when no decision named them; the first is
   * never skipped. A booked conversation has Booked current.
   */
  public List<Step> stages() {
    return Arrays.stream(Stage.values())
        .map(stage -> new Step(stage, state(stage, conversation.stage)))
        .toList();
  }

  /**
   * What the next message gets. The composer stays enabled after an end, so the hint says what a
   * message gets then.
   */
  public ComposerHint hint() {
    if (conversation.endReason.isHardTerminal()
        || conversation.status == ConversationStatus.RESET) {
      return ComposerHint.SILENT;
    }
    if (conversation.ended) {
      return ComposerHint.CLOSING_ONLY;
    }
    return conversation.needsHuman ? ComposerHint.HANDED_OFF : ComposerHint.READY;
  }

  /**
   * Goal met, unsubscribed, ended and needs human, in that order. Goal met reads as a pass; the
   * others warn when set, except an end that is the booking.
   */
  public List<Badge> badges() {
    boolean endedShort = conversation.ended && conversation.endReason != EndReason.BOOKED;
    return List.of(
        new Badge(
            "goal met", conversation.goalMet, conversation.goalMet ? Tone.PASS : Tone.PLAIN, null),
        new Badge(
            "unsubscribed", conversation.unsubscribed, warnIf(conversation.unsubscribed), null),
        new Badge(
            "ended",
            conversation.ended,
            warnIf(endedShort),
            conversation.ended ? conversation.status.name() : null),
        new Badge(
            "needs human",
            conversation.needsHuman,
            warnIf(conversation.needsHuman),
            conversation.needsHuman ? conversation.handoffReason.name() : null));
  }

  /** An opt-out or abuse after the booking: the booking stays for a person to decide. */
  public boolean bookingReview() {
    return conversation.handoffReason == HandoffReason.BOOKING_REVIEW;
  }

  public boolean healthInfo() {
    return conversation.flags().contains(Flag.HEALTH_INFO);
  }

  public boolean tzAssumed() {
    return conversation.flags().contains(Flag.TZ_ASSUMED);
  }

  /** The lead's fund, or "no provider". */
  public String provider() {
    return lead.hasProvider() ? lead.currentProvider() : "no provider";
  }

  public String premium() {
    return lead.currentPremium() == null ? "premium unknown" : "premium " + lead.currentPremium();
  }

  public String state() {
    return lead.state() == null ? "state unknown" : lead.state();
  }

  /** "Bupa, premium unknown, VIC → Australia/Melbourne": the record and the zone it resolved to. */
  public String leadRecord() {
    return provider() + ", " + premium() + ", " + state() + " → " + conversation.leadZoneId;
  }

  private static Tone warnIf(boolean set) {
    return set ? Tone.WARN : Tone.PLAIN;
  }

  private StepState state(Stage stage, Stage current) {
    int position = stage.compareTo(current);
    if (position > 0) {
      return StepState.AHEAD;
    }
    if (position == 0) {
      return StepState.CURRENT;
    }
    return stage == Stage.ENGAGE || seen.contains(stage) ? StepState.DONE : StepState.SKIPPED;
  }
}
