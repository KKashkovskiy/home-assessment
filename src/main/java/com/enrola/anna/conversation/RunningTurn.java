package com.enrola.anna.conversation;

import com.enrola.anna.lead.Lead;
import java.time.Instant;

/** The turn being run: what every step of {@link TurnPipeline} reads. */
record RunningTurn(Conversation conversation, Lead lead, Message inbound, Instant now) {

  int turnNo() {
    return inbound.turnNo();
  }
}
