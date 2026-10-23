package com.enrola.anna.eval;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.conversation.Message;
import com.enrola.anna.conversation.TraceEvent;
import com.enrola.anna.conversation.TraceEventType;
import com.enrola.anna.conversation.Turn;
import com.enrola.anna.conversation.TurnStatus;
import com.enrola.anna.guard.ReplyContext;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * What one turn left behind, read back from the rows: the conversation after it, the turn's number
 * and row (null when none was written), its trace events and outbound messages, and what its
 * replies are judged against.
 */
record TurnRows(
    Conversation conversation,
    int turnNo,
    Turn turn,
    List<TraceEvent> events,
    List<Message> outbound,
    ReplyContext replyContext) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The last message the turn sent, or null when it sent none. */
  String reply() {
    return outbound.isEmpty() ? null : outbound.getLast().text();
  }

  boolean modelCalled() {
    return events.stream().anyMatch(event -> event.type() == TraceEventType.MODEL_CALL);
  }

  /** The events of one type, in order. */
  List<TraceEvent> eventsOf(TraceEventType type) {
    return events.stream().filter(event -> event.type() == type).toList();
  }

  /** How the turn ended; null without a turn row. */
  TurnStatus status() {
    return turn == null ? null : turn.status();
  }

  /** The decision the turn applied, or null when it never reached the model. */
  Decision decision() {
    if (turn == null || turn.modelCall().decisionJson() == null) {
      return null;
    }
    return JSON.readValue(turn.modelCall().decisionJson(), Decision.class);
  }
}
