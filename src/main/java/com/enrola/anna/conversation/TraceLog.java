package com.enrola.anna.conversation;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one write path for trace events. The sequence is assigned here, so events are totally ordered
 * within a turn; the conversation's turn lock means no two writers race for a number.
 */
@Service
public class TraceLog {

  private final TraceEventRepository events;
  private final JsonMapper json;
  private final Clock clock;

  public TraceLog(TraceEventRepository events, JsonMapper json, Clock clock) {
    this.events = events;
    this.json = json;
    this.clock = clock;
  }

  /** What happened: the event's type, its name, how it came out, and a payload written as JSON. */
  public record Entry(TraceEventType type, String name, TraceOutcome outcome, Object payload) {}

  /** Appends {@code entry} as the turn's next event. */
  public void record(String conversationId, int turnNo, Entry entry) {
    events.save(
        new TraceEvent(
            UUID.randomUUID().toString(),
            conversationId,
            turnNo,
            events.lastSeq(conversationId, turnNo) + 1,
            entry.type(),
            entry.name(),
            entry.outcome(),
            json.writeValueAsString(entry.payload()),
            clock.instant()));
  }
}
