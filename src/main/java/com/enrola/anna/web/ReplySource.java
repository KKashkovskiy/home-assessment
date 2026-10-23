package com.enrola.anna.web;

import com.enrola.anna.conversation.MessageSource;
import com.enrola.anna.conversation.TurnStatus;

/**
 * What wrote a reply, as its chip says. FALLBACK is not a message source: it is a FIXED reply on a
 * turn whose status says the reply guards fell back to the template.
 */
public enum ReplySource {
  TEMPLATE,
  LLM,
  FIXED,
  FALLBACK;

  static ReplySource of(MessageSource source, TurnStatus turnStatus) {
    if (turnStatus == TurnStatus.FALLBACK) {
      return FALLBACK;
    }
    return switch (source) {
      case TEMPLATE -> TEMPLATE;
      case LLM -> LLM;
      case FIXED -> FIXED;
    };
  }
}
