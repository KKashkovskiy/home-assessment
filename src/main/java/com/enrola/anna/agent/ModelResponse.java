package com.enrola.anna.agent;

import java.util.List;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * What one round trip produced, with what it used and took and the raw JSON either way: the model's
 * output, function calls it wants answered first, or a failure, which carries a {@link Failure}
 * code, never a stack trace. {@link AgentService} switches over it exhaustively.
 */
public sealed interface ModelResponse permits ModelResponse.Final, ModelResponse.ToolCalls {

  Usage usage();

  long latencyMs();

  String rawRequestJson();

  String rawResponseJson();

  /** This round as a failure with {@code code}, keeping what it used, took and returned. */
  default Failed failed(Failure code, String detail) {
    return new Failed(code, detail, usage(), latencyMs(), rawRequestJson(), rawResponseJson());
  }

  /** A round that ends the call: the model's output, or a failure. */
  sealed interface Final extends ModelResponse permits Decided, Failed {}

  /**
   * The JSON the model returned, untyped: the transport does not know the schema, so a caller with
   * another schema reuses the client. {@link #decision()} maps it to the agent's record.
   */
  record Decided(
      JsonNode output,
      String reasoningSummary,
      Usage usage,
      long latencyMs,
      String rawRequestJson,
      String rawResponseJson)
      implements Final {

    /**
     * Strict on purpose: an unknown property, an unknown enum value or a coerced scalar ({@code 0}
     * for an enum, {@code "true"} for a boolean) means the output is not a decision; {@link
     * Decision} rejects a missing or null required field itself.
     */
    static final JsonMapper JSON =
        JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    /** The output as a {@link Decision}; throws {@link SchemaMismatch} when it is not one. */
    public Decision decision() {
      if (!output.isObject()) {
        throw new SchemaMismatch("output is not a JSON object");
      }
      try {
        return JSON.treeToValue(output, Decision.class);
      } catch (DatabindException e) {
        throw new SchemaMismatch(e.getOriginalMessage());
      }
    }
  }

  /**
   * The model asked for function calls before it answers. {@code replay} is every output item the
   * next round must see again, in order, its reasoning and the calls themselves; each of {@link
   * #calls()} is then answered with one {@link InputItem.FunctionOutput}.
   */
  record ToolCalls(
      List<InputItem> replay,
      Usage usage,
      long latencyMs,
      String rawRequestJson,
      String rawResponseJson)
      implements ModelResponse {

    public ToolCalls {
      replay = List.copyOf(replay);
    }

    /** The function calls among {@link #replay()}, in order. */
    public List<InputItem.FunctionCall> calls() {
      return replay.stream()
          .<InputItem.FunctionCall>mapMulti(
              (item, calls) -> {
                if (item instanceof InputItem.FunctionCall call) {
                  calls.accept(call);
                }
              })
          .toList();
    }
  }

  /** The round failed after the SDK's retries; {@code detail} says more, for the trace. */
  record Failed(
      Failure code,
      String detail,
      Usage usage,
      long latencyMs,
      String rawRequestJson,
      String rawResponseJson)
      implements Final {}

  /** Why a call failed. */
  enum Failure {
    /** No answer in time, or no connection, after the SDK's retries. */
    TIMEOUT,
    HTTP_5XX,
    HTTP_4XX,
    /** Any other SDK error. */
    SDK,
    /** The response stopped early, usually at {@code max_output_tokens}. */
    INCOMPLETE,
    /** The response ended failed, cancelled or still in progress. */
    FAILED,
    REFUSAL,
    /** A completed response with no output text. */
    NO_OUTPUT,
    /** The output was not JSON, or not the JSON the schema describes. */
    SCHEMA,
    /** The model still called tools when the turn had no tool round left. */
    TOOL_ROUNDS
  }

  /** Token counts for one call; {@code out} includes {@code reasoning}, as billed. */
  record Usage(int in, int cached, int out, int reasoning) {
    public static final Usage ZERO = new Usage(0, 0, 0, 0);

    /** This usage and {@code other}'s summed, for a turn that took several rounds. */
    public Usage plus(Usage other) {
      return new Usage(
          in + other.in, cached + other.cached, out + other.out, reasoning + other.reasoning);
    }
  }

  /** The output was JSON but not a {@link Decision}. */
  final class SchemaMismatch extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SchemaMismatch(String message) {
      super(message);
    }
  }
}
