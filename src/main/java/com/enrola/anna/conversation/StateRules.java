package com.enrola.anna.conversation;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.HandoffReason;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The conversation's state machine: the only code that sets {@code ended}, {@code endReason},
 * {@code status} after start, {@code unsubscribed}, {@code needsHuman} and {@code handoffReason},
 * and the rules for what may follow an end. Every switch lists every constant, so a new reason
 * fails to compile until it is placed.
 */
final class StateRules {

  /** The one closing reply after a soft-terminal end fits a single segment and asks nothing. */
  private static final int CLOSING_REPLY_MAX_CHARS = 160;

  private StateRules() {}

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
    appendHistory(conversation, reason, at);
  }

  /**
   * Hands off SELF_HARM and ends SAFETY. After a hard-terminal end the end reason stays and SAFETY
   * is only added to the history, but the handoff happens even on an unsubscribed conversation, the
   * one place that ignores the opt-out: safety beats silence.
   */
  static void safety(Conversation conversation, Instant at) {
    if (conversation.endReason.isHardTerminal()) {
      appendHistory(conversation, EndReason.SAFETY, at);
      conversation.needsHuman = true;
      conversation.handoffReason = HandoffReason.SELF_HARM;
      return;
    }
    handoff(conversation, HandoffReason.SELF_HARM);
    end(conversation, EndReason.SAFETY, at);
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

  /** True while no closing reply has gone out and {@code reply} is short and asks nothing. */
  static boolean closingReplyAllowed(Conversation conversation, String reply) {
    return conversation.postTerminalReplies == 0
        && reply.length() <= CLOSING_REPLY_MAX_CHARS
        && !reply.contains("?");
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

  private static void appendHistory(Conversation conversation, EndReason reason, Instant at) {
    List<EndReasonEntry> history = new ArrayList<>(conversation.endReasonHistory());
    history.add(new EndReasonEntry(reason, at));
    conversation.setEndReasonHistory(history);
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
