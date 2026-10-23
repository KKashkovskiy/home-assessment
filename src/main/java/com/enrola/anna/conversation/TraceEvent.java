package com.enrola.anna.conversation;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;

/**
 * One thing that happened inside a turn, {@code seq} ordering it among the turn's events. Written
 * only by {@link TraceLog}; insert-only, so {@link #isNew()} is always true.
 */
public record TraceEvent(
    @Id String id,
    String conversationId,
    int turnNo,
    int seq,
    TraceEventType type,
    String name,
    TraceOutcome outcome,
    String payloadJson,
    Instant createdAt)
    implements Persistable<String> {

  @Override
  public String getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return true;
  }
}
