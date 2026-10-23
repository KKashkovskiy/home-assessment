package com.enrola.anna.conversation;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;

/**
 * One finished inbound turn. {@code responseJson} is the response the platform got, replayed when
 * it retries the same provider message id and read by nothing else. Insert-only like {@link
 * Message}, so {@link #isNew()} is always true.
 */
public record Turn(
    @Id String id,
    String conversationId,
    int turnNo,
    String inboundMessageId,
    TurnStatus status,
    String responseJson,
    Instant createdAt)
    implements Persistable<String> {

  /** The turn that answered {@code inbound}, under its turn number. */
  public static Turn of(Message inbound, TurnStatus status, String responseJson, Instant at) {
    return new Turn(
        UUID.randomUUID().toString(),
        inbound.conversationId(),
        inbound.turnNo(),
        inbound.id(),
        status,
        responseJson,
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
