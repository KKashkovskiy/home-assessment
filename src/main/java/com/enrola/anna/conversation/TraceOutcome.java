package com.enrola.anna.conversation;

/** How a trace event came out; INFO for an event that is a record rather than a verdict. */
public enum TraceOutcome {
  PASS,
  BLOCK,
  OVERRIDE,
  REPAIR,
  INFO
}
