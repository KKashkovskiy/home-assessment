package com.enrola.anna.conversation;

/**
 * A conversation or lead lock was not free in time. A conflict with code BUSY, so the platform can
 * retry with the same provider message id.
 */
public class BusyException extends ConflictException {

  public static final String CODE = "BUSY";

  private BusyException(String message, String conversationId) {
    super(CODE, message, conversationId);
  }

  static BusyException conversation(String conversationId) {
    return new BusyException(
        "conversation " + conversationId + " is processing a turn", conversationId);
  }

  static BusyException lead(String leadId) {
    return new BusyException("lead " + leadId + " is busy", null);
  }
}
