package com.enrola.anna.conversation;

/**
 * The request clashes with an existing conversation, which this names. {@code code} says why; the
 * message is client-facing.
 */
public class ConflictException extends RuntimeException {

  private final String code;
  private final String conversationId;

  public ConflictException(String code, String message, String conversationId) {
    super(message);
    this.code = code;
    this.conversationId = conversationId;
  }

  public String code() {
    return code;
  }

  public String conversationId() {
    return conversationId;
  }
}
