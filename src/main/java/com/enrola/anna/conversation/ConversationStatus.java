package com.enrola.anna.conversation;

/**
 * The one column a list filters on: ACTIVE, HANDOFF while a human is needed and the conversation is
 * open, or the {@link EndReason} of the same name once it has ended.
 */
public enum ConversationStatus {
  ACTIVE,
  BOOKED,
  DECLINED,
  UNSUBSCRIBED,
  ABUSIVE,
  OFF_TOPIC_LIMIT,
  MAX_TURNS,
  HANDOFF,
  SAFETY
}
