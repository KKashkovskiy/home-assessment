package com.enrola.anna.conversation;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;

/**
 * One SMS, either direction. {@code source} is null for inbound. Insert-only: a message row is
 * never updated, so {@link #isNew()} is always true and {@code save} inserts, which a record with a
 * pre-assigned id would otherwise not do.
 */
public record Message(
    @Id String id,
    String conversationId,
    int turnNo,
    Direction direction,
    String text,
    int charCount,
    String providerMessageId,
    MessageSource source,
    boolean repaired,
    Instant createdAt)
    implements Persistable<String> {

  public static Message outbound(
      String conversationId, int turnNo, String text, MessageSource source, Instant at) {
    return new Message(
        UUID.randomUUID().toString(),
        conversationId,
        turnNo,
        Direction.OUT,
        text,
        text.length(),
        null,
        source,
        false,
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
