package com.enrola.anna.agent;

import java.util.List;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * What one call produced, with what it used and took and the raw JSON either way. A call that
 * answered function calls on the way covers all its rounds: usage and latency are their sum, the
 * raw JSON is the last round's, and {@link #toolCalls()} lists the calls answered. A failure
 * carries a {@link Failure} code, never a stack trace.
 */
public sealed interface ModelResponse permits ModelResponse.Decided, ModelResponse.Failed {

  Usage usage();

  long latencyMs();

  String rawRequestJson();

  String rawResponseJson();

  /** The function calls answered on the way, in order; empty when there were none. */
  List<ToolCall> toolCalls();

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
      String rawResponseJson,
      List<ToolCall> toolCalls)
      implements ModelResponse {

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

    /** This call as a failure with {@code code}, keeping what it used, took, returned and ran. */
    public Failed failed(Failure code, String detail) {
      return new Failed(code, detail, usage, latencyMs, rawRequestJson, rawResponseJson, toolCalls);
    }
  }

  /** The call failed after the SDK's retries; {@code detail} says more, for the trace. */
  record Failed(
      Failure code,
      String detail,
      Usage usage,
      long latencyMs,
      String rawRequestJson,
      String rawResponseJson,
      List<ToolCall> toolCalls)
      implements ModelResponse {}

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
    /** The model still called tools after the rounds the request allows. */
    TOOL_ROUNDS
  }

  /** Token counts for one call; {@code out} includes {@code reasoning}, as billed. */
  record Usage(int in, int cached, int out, int reasoning) {
    public static final Usage ZERO = new Usage(0, 0, 0, 0);

    /** This usage and {@code other}'s summed, for a call that took several rounds. */
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
