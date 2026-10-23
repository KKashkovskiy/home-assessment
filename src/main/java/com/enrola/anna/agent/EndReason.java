package com.enrola.anna.agent;

/** Why a conversation ended; NONE while it is open. {@code StateRules} ranks them. */
public enum EndReason {
  NONE,
  BOOKED,
  DECLINED,
  UNSUBSCRIBED,
  ABUSIVE,
  OFF_TOPIC_LIMIT,
  MAX_TURNS,
  HANDOFF,
  SAFETY
}
