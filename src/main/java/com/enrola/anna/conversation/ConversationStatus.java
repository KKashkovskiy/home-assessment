package com.enrola.anna.conversation;

import com.enrola.anna.agent.EndReason;

/**
 * The one column a list filters on: ACTIVE, HANDOFF while a human is needed and the conversation is
 * open, the {@link EndReason} of the same name once it has ended, or RESET when the console started
 * over with the same lead before this one ended.
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
  SAFETY,
  RESET
}
