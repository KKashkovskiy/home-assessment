package com.enrola.anna.conversation;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/** Trace event rows, unique by conversation, turn number and sequence. */
public interface TraceEventRepository extends CrudRepository<TraceEvent, String> {

  /** The turn's highest sequence number so far; 0 before its first event. */
  @Query(
      "select coalesce(max(seq), 0) from trace_event"
          + " where conversation_id = :conversationId and turn_no = :turnNo")
  int lastSeq(String conversationId, int turnNo);
}
