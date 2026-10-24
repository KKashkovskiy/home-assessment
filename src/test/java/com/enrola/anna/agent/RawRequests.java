package com.enrola.anna.agent;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The raw request JSON a scripted round records, in the shape the SDK writes it: instructions and
 * input items by role, as far as anything reads it back. Replayed items go in as the JSON they
 * carry.
 */
final class RawRequests {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private RawRequests() {}

  /** {@code scripted} unchanged but for its raw request, which becomes {@code request}'s JSON. */
  static ModelResponse stamp(ModelResponse scripted, ModelRequest request) {
    String rawRequest = json(request);
    return switch (scripted) {
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

  private static String json(ModelRequest request) {
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
        case InputItem.Opaque opaque -> input.add(JSON.readTree(opaque.json()));
      }
    }
    return JSON.writeValueAsString(body);
  }
}
