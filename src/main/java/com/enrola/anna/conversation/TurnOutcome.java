package com.enrola.anna.conversation;

/**
 * What a turn sends, a reply and its source or nothing, and the model call it made, {@link
 * Turn.ModelCall#NONE} when it made none. {@code decidedBy} names the step that decided, for the
 * log.
 */
record TurnOutcome(
    String reply,
    MessageSource source,
    TurnStatus status,
    String decidedBy,
    Turn.ModelCall modelCall) {

  static TurnOutcome fixed(String reply, String decidedBy) {
    return new TurnOutcome(
        reply, MessageSource.FIXED, TurnStatus.OK, decidedBy, Turn.ModelCall.NONE);
  }

  static TurnOutcome silent(String decidedBy) {
    return new TurnOutcome(null, null, TurnStatus.NO_REPLY, decidedBy, Turn.ModelCall.NONE);
  }

  /** The model's decision: its reply, or nothing when it chose not to send one. */
  static TurnOutcome decided(String reply, Turn.ModelCall call) {
    return reply == null
        ? new TurnOutcome(null, null, TurnStatus.NO_REPLY, "model sent no reply", call)
        : new TurnOutcome(reply, MessageSource.LLM, TurnStatus.OK, "model decision", call);
  }

  static TurnOutcome failed(String decidedBy, Turn.ModelCall call) {
    return new TurnOutcome(null, null, TurnStatus.ERROR, decidedBy, call);
  }
}
