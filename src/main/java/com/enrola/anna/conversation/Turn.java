package com.enrola.anna.conversation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Embedded;

/**
 * One finished inbound turn. {@code responseJson} is the response the platform got, replayed when
 * it retries the same provider message id; {@code modelCall} is what the model returned and cost,
 * so the turn can explain itself. Insert-only like {@link Message}, so {@link #isNew()} is always
 * true.
 */
public record Turn(
    @Id String id,
    String conversationId,
    int turnNo,
    String inboundMessageId,
    TurnStatus status,
    String responseJson,
    @Embedded.Empty ModelCall modelCall,
    Instant createdAt)
    implements Persistable<String> {

  /**
   * One model call: the decision JSON, the model and prompt version, tokens, USD cost, latency, the
   * model's reasoning summary (never sent to the lead) and the raw request and response JSON. A
   * turn that never reached the model has {@link #NONE}: zero counts and null texts.
   */
  public record ModelCall(
      String decisionJson,
      String model,
      String promptVersion,
      int tokensIn,
      int tokensCached,
      int tokensOut,
      int tokensReasoning,
      BigDecimal costUsd,
      int latencyMs,
      String reasoningSummary,
      String rawRequestJson,
      String rawResponseJson) {

    public static final ModelCall NONE =
        new ModelCall(null, null, null, 0, 0, 0, 0, BigDecimal.ZERO, 0, null, null, null);
  }

  /** The turn that answered {@code inbound}, under its turn number, as {@code outcome} decided. */
  static Turn of(Message inbound, TurnOutcome outcome, String responseJson, Instant at) {
    return new Turn(
        UUID.randomUUID().toString(),
        inbound.conversationId(),
        inbound.turnNo(),
        inbound.id(),
        outcome.status(),
        responseJson,
        outcome.modelCall(),
        at);
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return true;
  }
}
