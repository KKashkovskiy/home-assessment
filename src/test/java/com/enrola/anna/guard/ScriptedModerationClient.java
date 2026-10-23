package com.enrola.anna.guard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Moderation in tests: a queue of canned verdicts, with every screened text kept for assertions.
 * With nothing queued the verdict is {@link #CLEAN}; a test queues only the verdict it wants acted
 * on.
 */
public final class ScriptedModerationClient implements ModerationClient {

  /** Every score zero, from an endpoint that answered. */
  public static final ModerationVerdict CLEAN = new ModerationVerdict(0, 0, 0, true);

  private final Deque<ModerationVerdict> scripted = new ArrayDeque<>();
  private final List<String> screened = new ArrayList<>();

  public ScriptedModerationClient enqueue(ModerationVerdict verdict) {
    scripted.add(verdict);
    return this;
  }

  public List<String> screened() {
    return List.copyOf(screened);
  }

  /** Forgets what was scripted and screened, so a shared client starts each test clean. */
  public void reset() {
    scripted.clear();
    screened.clear();
  }

  @Override
  public ModerationVerdict screen(String text) {
    screened.add(text);
    return scripted.isEmpty() ? CLEAN : scripted.poll();
  }
}
