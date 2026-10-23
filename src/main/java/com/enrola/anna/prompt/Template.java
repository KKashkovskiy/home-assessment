package com.enrola.anna.prompt;

/**
 * The ten fixed SMS templates and the character cap each rendering must fit. Openers may run to two
 * segments; the rest fit one.
 */
public enum Template {
  OPENER_PROVIDER(320),
  OPENER_NO_PROVIDER(320),
  UNSUBSCRIBE_CONFIRMATION(160),
  SELF_HARM_SAFETY(160),
  OFF_TOPIC_CLOSE(160),
  MAX_TURNS_CLOSE(160),
  DECLINED_CLOSE(160),
  BOOKING_CHANGE_HANDOFF(160),
  HANDOFF_ACK(160),
  FALLBACK(160);

  public final int maxChars;

  Template(int maxChars) {
    this.maxChars = maxChars;
  }
}
