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
  SAFETY;

  /**
   * True when later inbound messages are stored and never answered: the lead opted out, was
   * abusive, needs safety resources, or kept going off topic. The other ends are soft-terminal: the
   * model still classifies, and one closing reply is allowed.
   */
  public boolean isHardTerminal() {
    return switch (this) {
      case UNSUBSCRIBED, ABUSIVE, SAFETY, OFF_TOPIC_LIMIT -> true;
      case NONE, BOOKED, DECLINED, MAX_TURNS, HANDOFF -> false;
    };
  }
}
