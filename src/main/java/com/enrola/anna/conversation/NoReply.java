package com.enrola.anna.conversation;

/**
 * Why a turn sent nothing, stored with the turn's response so the console can say so instead of
 * leaving a gap. Null on a turn that sent a reply.
 */
public enum NoReply {
  /** The conversation had already ended hard: the message is stored and nothing more happens. */
  ENDED_BEFORE,
  /** This message ended the conversation with nothing to send: abuse, or the model ended it. */
  ENDED_NOW,
  /** The console reset the conversation; the lead's newer one carries on. */
  RESET,
  /** The lead repeated a request already acknowledged; flagged LOOP_SUPPRESSED. */
  REPEAT,
  /** After an end, a closing reply already went out or the reply guards rejected this one. */
  CLOSING_SUPPRESSED,
  /** After an end, the model chose to say nothing. */
  MODEL_SILENT,
  /**
   * A person takes over: the model could not be called or failed, decided not to reply before an
   * end, or wrote a reply the guards rejected while a handoff stands.
   */
  HANDOFF
}
