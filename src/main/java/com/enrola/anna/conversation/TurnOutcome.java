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

  /** The model's reply, sent as decided. */
  static TurnOutcome decided(String reply) {
    return new TurnOutcome(
        reply, MessageSource.LLM, TurnStatus.OK, "model decision", Turn.ModelCall.NONE);
  }

  static TurnOutcome failed(String decidedBy) {
    return new TurnOutcome(null, null, TurnStatus.ERROR, decidedBy, Turn.ModelCall.NONE);
  }

  /** This outcome, made by a turn that called the model, so the call is stored on the turn. */
  TurnOutcome withModelCall(Turn.ModelCall call) {
    return new TurnOutcome(reply, source, status, decidedBy, call);
  }
}
