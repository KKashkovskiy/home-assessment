package com.enrola.anna.agent;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Everything one call needs. {@code schema} is the JSON schema the output must fit strictly, named
 * {@code schemaName}. {@code tools} are the functions the model may call on the way to its output.
 * {@code reasoningEffort} null leaves reasoning at the model's default, and {@code
 * reasoningSummary} null asks for no summary.
 */
public record ModelRequest(
    String model,
    String instructions,
    List<InputItem> input,
    String schemaName,
    JsonNode schema,
    Tools tools,
    String reasoningEffort,
    String reasoningSummary,
    long maxOutputTokens) {

  /** The boot-time check: a few tokens of {@code {"ok": true}}, no tools, no reasoning. */
  public static ModelRequest ping(String model) {
    return new ModelRequest(
        model,
        "Reply with ok set to true.",
        List.of(new UserText("ping")),
        "ping",
        okSchema(),
        Tools.NONE,
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

  /**
   * The function tools the model may call, one {@code tools.json} entry each, and who answers them.
   * At most {@code maxRounds} rounds of calls are answered; the model must then decide.
   */
  public record Tools(List<JsonNode> definitions, ToolExecutor executor, int maxRounds) {

    /** No tools to call, so no round is ever answered. */
    public static final Tools NONE =
        new Tools(
            List.of(),
            (name, argumentsJson) -> {
              throw new IllegalStateException("no tools were offered, yet " + name + " ran");
            },
            0);
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
