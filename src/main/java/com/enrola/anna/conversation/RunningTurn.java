package com.enrola.anna.conversation;

import com.enrola.anna.lead.Lead;
import com.enrola.anna.prompt.PromptBundle;
import java.time.Instant;

/**
 * The turn being run: what every step of {@link TurnPipeline} reads. {@code bookedAtStart} says
 * whether the conversation had a booking row when the turn began; the row, not a flag, is what
 * makes CANCEL and a reschedule request mean the call. {@code prompt} is the version the
 * conversation pinned at start, whatever is active now; when that one cannot load, the active one,
 * for its fixed templates only.
 */
record RunningTurn(
    Conversation conversation,
    Lead lead,
    Message inbound,
    Instant now,
    boolean bookedAtStart,
    PromptBundle prompt) {

  int turnNo() {
    return inbound.turnNo();
  }

  /** False when {@code prompt} stands in for a pinned version that cannot load: no model call. */
  boolean onPinnedPrompt() {
    return prompt.id().equals(conversation.promptVersion);
  }
}
