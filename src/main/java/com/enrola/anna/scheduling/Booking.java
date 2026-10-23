package com.enrola.anna.scheduling;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;

/**
 * The one advisor call for a conversation, written by book_call only. {@code id} is the calendar's
 * event id, {@code localLabel} the slot's label in the lead's zone, the one booked time a reply may
 * name. There is no status: a row exists or it does not, and a change request is a handoff.
 * Insert-only, so {@link #isNew()} is always true and {@code save} inserts.
 */
public record Booking(
    @Id String id,
    String conversationId,
    String slotId,
    Instant startUtc,
    String localLabel,
    String leadZoneId,
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
