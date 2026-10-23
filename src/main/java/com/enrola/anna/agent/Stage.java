package com.enrola.anna.agent;

/** Where the script is. Every conversation starts at ENGAGE. */
public enum Stage {
  ENGAGE,
  PREFERENCE,
  CALL_ASK,
  OFFER_TIMES,
  BOOKED
}
