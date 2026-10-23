package com.enrola.anna.conversation;

import com.enrola.anna.lead.Lead;
import java.time.Instant;

/**
 * The turn being run: what every step of {@link TurnPipeline} reads. {@code bookedAtStart} says
 * whether the conversation had a booking row when the turn began; the row, not a flag, is what
 * makes CANCEL and a reschedule request mean the call.
 */
record RunningTurn(
    Conversation conversation, Lead lead, Message inbound, Instant now, boolean bookedAtStart) {

  int turnNo() {
    return inbound.turnNo();
  }
}
