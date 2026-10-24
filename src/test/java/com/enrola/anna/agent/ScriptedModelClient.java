package com.enrola.anna.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The model in tests: a queue of canned responses, one per round trip, with every request kept for
 * assertions. A request with nothing queued throws, so an unexpected call fails the test. Each
 * response comes back with its request as raw JSON ({@link RawRequests}), so what a turn stores
 * reads like a real one.
 */
public final class ScriptedModelClient implements ModelClient {

  private final Deque<ModelResponse> scripted = new ArrayDeque<>();
  private final List<ModelRequest> calls = new ArrayList<>();

  public ScriptedModelClient enqueue(ModelResponse response) {
    scripted.add(response);
    return this;
  }

  /** Queues a round that returns {@code decision}. */
  public ScriptedModelClient decide(Decision decision) {
    return enqueue(Decisions.decided(decision));
  }

  /** Queues a round in which the model calls the tool {@code name}. */
  public ScriptedModelClient callTool(String name, String argumentsJson) {
    return enqueue(Decisions.toolCall(name, argumentsJson));
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
    return RawRequests.stamp(scripted.poll(), request);
  }
}
