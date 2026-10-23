package com.enrola.anna.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The model in tests: a queue of canned responses, with every request kept for assertions. A call
 * with nothing scripted throws, so an unexpected model call fails the test instead of passing.
 */
public final class ScriptedModelClient implements ModelClient {

  private final Deque<ModelResponse> scripted = new ArrayDeque<>();
  private final List<ModelRequest> calls = new ArrayList<>();

  public ScriptedModelClient enqueue(ModelResponse response) {
    scripted.add(response);
    return this;
  }

  /** Scripts a successful call that returns {@code decision}. */
  public ScriptedModelClient decide(Decision decision) {
    return enqueue(Decisions.decided(decision));
  }

  public List<ModelRequest> calls() {
    return List.copyOf(calls);
  }

  /** Forgets what was scripted and asked, so a shared client starts each test clean. */
  public void reset() {
    scripted.clear();
    calls.clear();
  }

  @Override
  public ModelResponse call(ModelRequest request) {
    calls.add(request);
    if (scripted.isEmpty()) {
      throw new AssertionError("model called with nothing scripted");
    }
    return scripted.poll();
  }
}
