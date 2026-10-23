package com.enrola.anna.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The model in tests: a queue of canned rounds, with every request kept for assertions. A scripted
 * function call is answered through the request's {@link ToolExecutor} and the next round is taken,
 * capped like {@link OpenAiModelClient}'s loop, so tool calls run against the real tools. A call
 * with nothing scripted throws, so an unexpected model call fails the test instead of passing.
 */
public final class ScriptedModelClient implements ModelClient {

  /** One scripted round: a function call to answer, or the response that ends the call. */
  private sealed interface Round permits CallTool, Respond {}

  private record CallTool(String name, String argumentsJson) implements Round {}

  private record Respond(ModelResponse response) implements Round {}

  private final Deque<Round> scripted = new ArrayDeque<>();
  private final List<ModelRequest> calls = new ArrayList<>();

  public ScriptedModelClient enqueue(ModelResponse response) {
    scripted.add(new Respond(response));
    return this;
  }

  /** Scripts a successful call that returns {@code decision}. */
  public ScriptedModelClient decide(Decision decision) {
    return enqueue(Decisions.decided(decision));
  }

  /** Scripts a round in which the model calls the tool {@code name}. */
  public ScriptedModelClient callTool(String name, String argumentsJson) {
    scripted.add(new CallTool(name, argumentsJson));
    return this;
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
    List<ToolCall> answered = new ArrayList<>();
    for (int round = 0; round < request.tools().maxRounds(); round++) {
      switch (next()) {
        case Respond(ModelResponse response) -> {
          return withToolCalls(response, answered);
        }
        case CallTool(String name, String arguments) ->
            answered.add(
                new ToolCall(name, arguments, request.tools().executor().execute(name, arguments)));
      }
    }
    return switch (next()) {
      case Respond(ModelResponse response) -> withToolCalls(response, answered);
      case CallTool _ ->
          withToolCalls(Decisions.failed(ModelResponse.Failure.TOOL_ROUNDS), answered);
    };
  }

  private Round next() {
    if (scripted.isEmpty()) {
      throw new AssertionError("model called with nothing scripted");
    }
    return scripted.poll();
  }

  private static ModelResponse withToolCalls(ModelResponse response, List<ToolCall> answered) {
    List<ToolCall> toolCalls = List.copyOf(answered);
    return switch (response) {
      case ModelResponse.Decided decided ->
          new ModelResponse.Decided(
              decided.output(),
              decided.reasoningSummary(),
              decided.usage(),
              decided.latencyMs(),
              decided.rawRequestJson(),
              decided.rawResponseJson(),
              toolCalls);
      case ModelResponse.Failed failed ->
          new ModelResponse.Failed(
              failed.code(),
              failed.detail(),
              failed.usage(),
              failed.latencyMs(),
              failed.rawRequestJson(),
              failed.rawResponseJson(),
              toolCalls);
    };
  }
}
