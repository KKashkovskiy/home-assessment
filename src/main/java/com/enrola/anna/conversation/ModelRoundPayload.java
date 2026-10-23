package com.enrola.anna.conversation;

import com.enrola.anna.agent.ModelResponse;
import java.math.BigDecimal;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The payload of a MODEL_CALL event: one round of a call, numbered across the turn, which call it
 * belongs to, what it used, cost and took, and how it ended. {@code failure} is set only when the
 * round failed, and {@code summary}, the model's reasoning summary, only when it decided; the call
 * and the status are never null.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelRoundPayload(
    String model,
    String effort,
    int round,
    Call call,
    ModelResponse.Usage tokens,
    BigDecimal costUsd,
    long latencyMs,
    Status status,
    ModelResponse.Failure failure,
    String summary) {

  public ModelRoundPayload {
    Objects.requireNonNull(call, "call");
    Objects.requireNonNull(status, "status");
  }

  /** The turn's decision call, or the one repair of a reply the guards rejected. */
  public enum Call {
    DECIDE,
    REPAIR
  }

  /** The round decided, asked for tool calls first, or failed. */
  public enum Status {
    DECIDED,
    TOOL_CALLS,
    FAILED
  }
}
