package com.enrola.anna.conversation;

import com.enrola.anna.agent.EndReason;
import java.time.Instant;

/**
 * The eval harness's way into {@link StateRules}, which stays package-private: a seeded booking or
 * end is set by the same rules a turn uses, never by assigning fields.
 */
public final class SeededState {

  private SeededState() {}

  public static void booked(Conversation conversation, Instant at) {
    StateRules.booked(conversation, at);
  }

  public static void end(Conversation conversation, EndReason reason, Instant at) {
    StateRules.end(conversation, reason, at);
  }
}
