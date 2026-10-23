package com.enrola.anna.conversation;

/** What produced an outbound SMS. Inbound messages have none. */
public enum MessageSource {
  TEMPLATE,
  LLM,
  FIXED
}
