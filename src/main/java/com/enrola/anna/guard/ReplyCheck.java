package com.enrola.anna.guard;

import java.util.Locale;

/** The checks a model reply must pass, in the order they are traced. */
public enum ReplyCheck {
  EMPTY,
  LENGTH,
  ONE_QUESTION,
  CLOSING_QUESTION,
  EXCLAMATION,
  CANARY,
  FORMAT,
  URL,
  PII,
  DENY,
  TIME_MENTION,
  MAX_SLOTS;

  /** The name in the trace and the repair note: "one-question". */
  public String traceName() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
