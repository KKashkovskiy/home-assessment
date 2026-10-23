package com.enrola.anna.agent;

import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * What one call produced, with what it used and took and the raw JSON either way. A failure carries
 * a {@link Failure} code, never a stack trace.
 */
public sealed interface ModelResponse permits ModelResponse.Decided, ModelResponse.Failed {

  Usage usage();

  long latencyMs();

  String rawRequestJson();

  String rawResponseJson();

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

    /** This call as a failure with {@code code}, keeping what it used, took and returned. */
    public Failed failed(Failure code, String detail) {
      return new Failed(code, detail, usage, latencyMs, rawRequestJson, rawResponseJson);
    }
  }

  /** The call failed after the SDK's retries; {@code detail} says more, for the trace. */
  record Failed(
      Failure code,
      String detail,
      Usage usage,
      long latencyMs,
      String rawRequestJson,
      String rawResponseJson)
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
    SCHEMA
  }

  /** Token counts for one call; {@code out} includes {@code reasoning}, as billed. */
  record Usage(int in, int cached, int out, int reasoning) {
    public static final Usage ZERO = new Usage(0, 0, 0, 0);
  }

  /** The output was JSON but not a {@link Decision}. */
  final class SchemaMismatch extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SchemaMismatch(String message) {
      super(message);
    }
  }
}
