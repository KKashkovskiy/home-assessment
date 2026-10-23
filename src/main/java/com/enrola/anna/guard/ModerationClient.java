package com.enrola.anna.guard;

/**
 * Screens one inbound text. {@link OpenAiModerationClient} when a key is set, {@link #NONE} without
 * one, and a scripted fake in tests.
 */
public interface ModerationClient {

  /** No moderation endpoint: every verdict is unavailable, so the turn goes on and says so. */
  ModerationClient NONE = _ -> ModerationVerdict.UNAVAILABLE;

  ModerationVerdict screen(String text);
}
