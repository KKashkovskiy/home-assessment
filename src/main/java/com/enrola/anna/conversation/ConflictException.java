package com.enrola.anna.conversation;

/**
 * The request clashes with the state of a lead or conversation. {@code code} says why, the message
 * is client-facing, and {@code conversationId} names the conversation involved when there is one.
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
