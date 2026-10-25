package com.enrola.anna.conversation;

/**
 * What a turn sends (a reply and its source, or nothing and why) and the model calls it made,
 * {@link Turn.ModelCall#NONE} when it made none. {@code decidedBy} names the step that decided, for
 * the log.
 */
record TurnOutcome(
    String reply,
    MessageSource source,
    NoReply noReply,
    TurnStatus status,
    String decidedBy,
    Turn.ModelCall modelCall) {

  static TurnOutcome fixed(String reply, String decidedBy) {
    return new TurnOutcome(
        reply, MessageSource.FIXED, null, TurnStatus.OK, decidedBy, Turn.ModelCall.NONE);
  }

  static TurnOutcome silent(NoReply why, String decidedBy) {
    return new TurnOutcome(null, null, why, TurnStatus.NO_REPLY, decidedBy, Turn.ModelCall.NONE);
  }

  /** The model's reply, sent as decided once normalised. */
  static TurnOutcome decided(String reply) {
    return new TurnOutcome(
        reply, MessageSource.LLM, null, TurnStatus.OK, "model decision", Turn.ModelCall.NONE);
  }

  /** The model's reply that passed the reply guards only on the repair call. */
  static TurnOutcome repaired(String reply) {
    return new TurnOutcome(
        reply, MessageSource.LLM, null, TurnStatus.REPAIRED, "repaired reply", Turn.ModelCall.NONE);
  }

  /** A fixed template sent in place of a model reply the repair could not fix. */
  static TurnOutcome fallback(String reply) {
    return new TurnOutcome(
        reply, MessageSource.FIXED, null, TurnStatus.FALLBACK, "fallback", Turn.ModelCall.NONE);
  }

  /** A turn that failed hands off, so nothing is sent while a person looks. */
  static TurnOutcome failed(String decidedBy) {
    return new TurnOutcome(
        null, null, NoReply.HANDOFF, TurnStatus.ERROR, decidedBy, Turn.ModelCall.NONE);
  }

  boolean repaired() {
    return status == TurnStatus.REPAIRED;
  }

  /** This outcome with the repair call, whose cost the turn adds to the applied call's. */
  TurnOutcome withRepairCall(Turn.ModelCall repair) {
    return new TurnOutcome(reply, source, noReply, status, decidedBy, repair);
  }

  /**
   * This outcome as made by {@code applied}, the call whose decision the turn applied, which the
   * turn row stores; a repair call the outcome carries is added to its cost.
   */
  TurnOutcome addModelCall(Turn.ModelCall applied) {
    return new TurnOutcome(reply, source, noReply, status, decidedBy, applied.plus(modelCall));
  }
}
