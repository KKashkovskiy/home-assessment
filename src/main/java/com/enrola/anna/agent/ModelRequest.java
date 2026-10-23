package com.enrola.anna.agent;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Everything one call needs. {@code schema} is the JSON schema the output must fit strictly, named
 * {@code schemaName}. {@code reasoningEffort} null leaves reasoning at the model's default, and
 * {@code reasoningSummary} null asks for no summary.
 */
public record ModelRequest(
    String model,
    String instructions,
    List<InputItem> input,
    String schemaName,
    JsonNode schema,
    String reasoningEffort,
    String reasoningSummary,
    long maxOutputTokens) {

  /** The boot-time check: a few tokens of {@code {"ok": true}}, no reasoning. */
  public static ModelRequest ping(String model) {
    return new ModelRequest(
        model,
        "Reply with ok set to true.",
        List.of(new UserText("ping")),
        "ping",
        okSchema(),
        null,
        null,
        64);
  }

  private static JsonNode okSchema() {
    JsonNodeFactory nodes = JsonNodeFactory.instance;
    ObjectNode schema = nodes.objectNode().put("type", "object").put("additionalProperties", false);
    schema.putArray("required").add("ok");
    schema.putObject("properties").putObject("ok").put("type", "boolean");
    return schema;
  }

  /** One input message, in transcript order. */
  public sealed interface InputItem permits UserText, AssistantText {
    String text();
  }

  /** What the lead sent, or the per-turn context. */
  public record UserText(String text) implements InputItem {}

  /** What Anna sent, the template opener included. */
  public record AssistantText(String text) implements InputItem {}
}
