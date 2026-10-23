package com.enrola.anna.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The model in tests: a queue of canned responses, one per round trip, with every request kept for
 * assertions. A request with nothing queued throws, so an unexpected call fails the test. Each
 * response carries its request as raw JSON in the shape the SDK writes, instructions and input
 * items by role, so what a turn stores reads like a real one.
 */
public final class ScriptedModelClient implements ModelClient {

  private static final JsonMapper JSON = JsonMapper.builder().build();

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
    String rawRequest = rawRequest(request);
    return switch (scripted.poll()) {
      case ModelResponse.Decided decided ->
          new ModelResponse.Decided(
              decided.output(),
              decided.reasoningSummary(),
              decided.usage(),
              decided.latencyMs(),
              rawRequest,
              decided.rawResponseJson());
      case ModelResponse.ToolCalls asked ->
          new ModelResponse.ToolCalls(
              asked.replay(),
              asked.usage(),
              asked.latencyMs(),
              rawRequest,
              asked.rawResponseJson());
      case ModelResponse.Failed failed ->
          new ModelResponse.Failed(
              failed.code(),
              failed.detail(),
              failed.usage(),
              failed.latencyMs(),
              rawRequest,
              failed.rawResponseJson());
    };
  }

  /** The request as the SDK serialises it, as far as anything reads it back. */
  private static String rawRequest(ModelRequest request) {
    ObjectNode body =
        JSON.createObjectNode()
            .put("model", request.model())
            .put("instructions", request.instructions())
            .put("store", false);
    ArrayNode input = body.putArray("input");
    for (InputItem item : request.input()) {
      switch (item) {
        case InputItem.UserText user ->
            input.addObject().put("role", "user").put("content", user.text());
        case InputItem.AssistantText anna ->
            input.addObject().put("role", "assistant").put("content", anna.text());
        case InputItem.FunctionCall call ->
            input
                .addObject()
                .put("type", "function_call")
                .put("call_id", call.callId())
                .put("name", call.name())
                .put("arguments", call.argumentsJson());
        case InputItem.FunctionOutput output ->
            input
                .addObject()
                .put("type", "function_call_output")
                .put("call_id", output.callId())
                .put("output", output.outputJson());
        case InputItem.Opaque _ -> input.addObject().put("type", "reasoning");
      }
    }
    return JSON.writeValueAsString(body);
  }
}
