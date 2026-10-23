package com.enrola.anna.conversation;

/**
 * A lead, conversation or turn that does not exist. The message, kind then id, is client-facing.
 */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String kind, String id) {
    super(kind + " " + id);
  }
}
