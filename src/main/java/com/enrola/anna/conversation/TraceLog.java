package com.enrola.anna.conversation;

import com.enrola.anna.AppClock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one write path for trace events. The sequence is assigned here, so events are totally ordered
 * within a turn; the conversation's turn lock means no two writers race for a number. Each event is
 * published once stored, for the inspector's live log.
 */
@Service
public class TraceLog {

  private final TraceEventRepository events;
  private final TracePublisher publisher;
  private final JsonMapper json;
  private final AppClock clock;

  public TraceLog(
      TraceEventRepository events, TracePublisher publisher, JsonMapper json, AppClock clock) {
    this.events = events;
    this.publisher = publisher;
    this.json = json;
    this.clock = clock;
  }

  /** What happened: the event's type, its name, how it came out, and a payload written as JSON. */
  public record Entry(TraceEventType type, String name, TraceOutcome outcome, Object payload) {

    /** A guard's verdict, written for a pass as well as a block. */
    public static Entry guard(String name, TraceOutcome outcome, Object payload) {
      return new Entry(TraceEventType.GUARD, name, outcome, payload);
    }
  }

  /** Appends {@code entry} as the running turn's next event. */
  void record(RunningTurn turn, Entry entry) {
    record(turn.conversation().id, turn.turnNo(), entry);
  }

  /** Appends {@code entry} as the turn's next event. */
  public void record(String conversationId, int turnNo, Entry entry) {
    TraceEvent event =
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
                clock.now()));
    publishOnceStored(event);
  }

  /**
   * At once outside a transaction; inside one, once it commits, so a listener never hears of a row
   * that is not there yet, nor of one rolled back.
   */
  private void publishOnceStored(TraceEvent event) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      publisher.publish(event);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            publisher.publish(event);
          }
        });
  }
}
