package com.enrola.anna.agent;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Everything one round trip needs. {@code schema} is the JSON schema the output must fit strictly,
 * named {@code schemaName}. {@code tools} are the function definitions the model may call instead,
 * one {@code tools.json} entry each; empty offers none. {@code reasoningEffort} null leaves
 * reasoning at the model's default, and {@code reasoningSummary} null asks for no summary.
 */
public record ModelRequest(
    String model,
    String instructions,
    List<InputItem> input,
    String schemaName,
    JsonNode schema,
    List<JsonNode> tools,
    String reasoningEffort,
    String reasoningSummary,
    long maxOutputTokens) {

  public ModelRequest {
    input = List.copyOf(input);
    tools = List.copyOf(tools);
  }

  /** The boot-time check: a few tokens of {@code {"ok": true}}, no tools, no reasoning. */
  public static ModelRequest ping(String model) {
    return new ModelRequest(
        model,
        "Reply with ok set to true.",
        List.of(new InputItem.UserText("ping")),
        "ping",
        okSchema(),
        List.of(),
        null,
        null,
        64);
  }

  /** This request with {@code next} as its input, for the next round of the same call. */
  ModelRequest withInput(List<InputItem> next) {
    return new ModelRequest(
        model,
        instructions,
        next,
        schemaName,
        schema,
        tools,
        reasoningEffort,
        reasoningSummary,
        maxOutputTokens);
  }

  private static JsonNode okSchema() {
    JsonNodeFactory nodes = JsonNodeFactory.instance;
    ObjectNode schema = nodes.objectNode().put("type", "object").put("additionalProperties", false);
    schema.putArray("required").add("ok");
    schema.putObject("properties").putObject("ok").put("type", "boolean");
    return schema;
  }
}
