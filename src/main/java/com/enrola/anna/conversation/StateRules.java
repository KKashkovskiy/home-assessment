package com.enrola.anna.conversation;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.HandoffReason;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The conversation's state machine: the only code that sets {@code ended}, {@code endReason},
 * {@code status} after start, {@code unsubscribed}, {@code needsHuman} and {@code handoffReason}.
 * Every switch lists every constant, so a new reason fails to compile until it is placed.
 */
final class StateRules {

  private StateRules() {}

  /**
   * True when later inbound messages are stored and never answered: the lead opted out, was
   * abusive, needs safety resources, or kept going off topic.
   */
  static boolean isHardTerminal(EndReason reason) {
    return switch (reason) {
      case UNSUBSCRIBED, ABUSIVE, SAFETY, OFF_TOPIC_LIMIT -> true;
      case NONE, BOOKED, DECLINED, MAX_TURNS, HANDOFF -> false;
    };
  }

  /**
   * Ends the conversation with {@code reason} unless the current reason ranks the same or higher,
   * and records the transition. Nothing outranks UNSUBSCRIBED, so an opt-out is final.
   */
  static void end(Conversation conversation, EndReason reason, Instant at) {
    if (reason == EndReason.NONE) {
      throw new IllegalArgumentException("a conversation cannot end with NONE");
    }
    if (conversation.ended && rank(reason) <= rank(conversation.endReason)) {
      return;
    }
    conversation.ended = true;
    conversation.endReason = reason;
    conversation.status = statusOf(reason);
    conversation.unsubscribed = reason == EndReason.UNSUBSCRIBED;
    List<EndReasonEntry> history = new ArrayList<>(conversation.endReasonHistory());
    history.add(new EndReasonEntry(reason, at));
    conversation.setEndReasonHistory(history);
  }

  /**
   * Asks for a human: {@code needsHuman} stays set for good and {@code handoffReason} becomes the
   * latest reason. An open conversation shows HANDOFF. Ignored once the lead opted out, since no
   * one follows up an opt-out.
   */
  static void handoff(Conversation conversation, HandoffReason reason) {
    if (reason == HandoffReason.NONE) {
      throw new IllegalArgumentException("a handoff needs a reason");
    }
    if (conversation.unsubscribed) {
      return;
    }
    conversation.needsHuman = true;
    conversation.handoffReason = reason;
    if (!conversation.ended) {
      conversation.status = ConversationStatus.HANDOFF;
    }
  }

  /** UNSUBSCRIBED over SAFETY over ABUSIVE over the rest. */
  private static int rank(EndReason reason) {
    return switch (reason) {
      case NONE -> 0;
      case BOOKED, DECLINED, OFF_TOPIC_LIMIT, MAX_TURNS, HANDOFF -> 1;
      case ABUSIVE -> 2;
      case SAFETY -> 3;
      case UNSUBSCRIBED -> 4;
    };
  }

  private static ConversationStatus statusOf(EndReason reason) {
    return switch (reason) {
      case NONE -> ConversationStatus.ACTIVE;
      case BOOKED -> ConversationStatus.BOOKED;
      case DECLINED -> ConversationStatus.DECLINED;
      case UNSUBSCRIBED -> ConversationStatus.UNSUBSCRIBED;
      case ABUSIVE -> ConversationStatus.ABUSIVE;
      case OFF_TOPIC_LIMIT -> ConversationStatus.OFF_TOPIC_LIMIT;
      case MAX_TURNS -> ConversationStatus.MAX_TURNS;
      case HANDOFF -> ConversationStatus.HANDOFF;
      case SAFETY -> ConversationStatus.SAFETY;
    };
  }
}
