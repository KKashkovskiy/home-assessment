package com.enrola.anna.conversation;

/**
 * What a trace event records: a guard verdict, a model call, a tool call, a state diff, an error.
 */
public enum TraceEventType {
  GUARD,
  MODEL_CALL,
  TOOL_CALL,
  STATE_DIFF,
  ERROR
}
