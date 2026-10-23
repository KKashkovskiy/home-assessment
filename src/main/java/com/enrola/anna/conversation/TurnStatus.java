package com.enrola.anna.conversation;

/**
 * How a turn ended, as stored on its row: OK sent the reply as decided, NO_REPLY sent nothing,
 * REPAIRED and FALLBACK sent a reply a guard rewrote or replaced, ERROR failed.
 */
public enum TurnStatus {
  OK,
  NO_REPLY,
  REPAIRED,
  FALLBACK,
  ERROR
}
