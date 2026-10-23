package com.enrola.anna.conversation;

/** A lead or conversation id that does not exist. The message, kind then id, is client-facing. */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String kind, String id) {
    super(kind + " " + id);
  }
}
