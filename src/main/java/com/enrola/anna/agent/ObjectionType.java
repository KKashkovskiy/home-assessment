package com.enrola.anna.agent;

/** Why the lead pushed back; NONE unless the intent is OBJECTION. Mirrors the decision schema. */
public enum ObjectionType {
  NONE,
  BUSY,
  NOT_INTERESTED,
  ALREADY_COVERED,
  PRICE,
  DISTRUST,
  OTHER
}
