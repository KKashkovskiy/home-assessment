package com.enrola.anna.conversation;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.Stage;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where a model decision changes a conversation: its classification, and the ending or handoff it
 * asks for, through {@link StateRules}. What to send is the caller's business, and the counts
 * belong to {@link TurnCounters}. Fields code owns are never taken from the model: a claimed goal,
 * a BOOKED stage or an end reason the model may not set is traced as overridden and changes
 * nothing.
 */
@Component
class DecisionApplier {

  /** The width of the summary column. */
  private static final int SUMMARY_MAX = 200;

  private final TraceLog trace;
  private final JsonMapper json;

  DecisionApplier(TraceLog trace, JsonMapper json) {
    this.trace = trace;
    this.json = json;
  }

  /**
   * What every decision leaves, whatever becomes of its reply: stage, flags, extracted facts and
   * summary. Until a booking exists the goal is not met and the stage is not BOOKED; once one does,
   * the stage stays BOOKED.
   */
  void classify(RunningTurn turn, Decision decision) {
    Conversation conversation = turn.conversation();
    conversation.stage = stageOf(turn, decision.stage());
    if (decision.goalMet() && !conversation.goalMet) {
      override(turn, "goal_met", Map.of("model", true, "code", false));
    }
    decision.flags().forEach(conversation::addFlag);
    conversation.extractedJson = merged(conversation.extractedJson, decision.extracted());
    if (decision.summary() != null) {
      conversation.summary = TurnQuestions.cut(decision.summary(), SUMMARY_MAX);
    }
  }

  /**
   * Code owns BOOKED: a booking keeps the stage there, and the model's claim of it is overridden.
   */
  private Stage stageOf(RunningTurn turn, Stage claimed) {
    if (turn.conversation().goalMet) {
      return Stage.BOOKED;
    }
    if (claimed == Stage.BOOKED) {
      override(turn, "stage", Map.of("model", Stage.BOOKED, "code", Stage.OFFER_TIMES));
      return Stage.OFFER_TIMES;
    }
    return claimed;
  }

  /** The handoff the model asks for, then the ending, each only with its flag set. */
  void applyEnding(RunningTurn turn, Decision decision) {
    if (decision.needsHuman()) {
      handOff(turn, decision.handoffReason());
    }
    if (decision.conversationEnded()) {
      end(turn, decision.endReason());
    }
  }

  /**
   * A request for a person or a complaint hands the thread over and ends it HANDOFF; DISTRESS and
   * the rest stay open, so Anna may answer gently.
   */
  private static void handOff(RunningTurn turn, HandoffReason reason) {
    switch (reason) {
      case NONE -> {}
      case REQUESTED, COMPLAINT -> {
        StateRules.handoff(turn.conversation(), reason);
        StateRules.end(turn.conversation(), EndReason.HANDOFF, turn.now());
      }
      case DISTRESS, SELF_HARM, RESCHEDULE, ERROR, BOOKING_REVIEW ->
          StateRules.handoff(turn.conversation(), reason);
    }
  }

  /**
   * The model may end DECLINED or HANDOFF; every other end belongs to a code path, and the model
   * naming the end code already made, as on the turn book_call booked, overrides nothing.
   */
  private void end(RunningTurn turn, EndReason reason) {
    switch (reason) {
      case NONE -> {}
      case DECLINED, HANDOFF -> StateRules.end(turn.conversation(), reason, turn.now());
      case BOOKED, UNSUBSCRIBED, ABUSIVE, OFF_TOPIC_LIMIT, MAX_TURNS, SAFETY ->
          overrideUnlessCurrent(turn, reason);
    }
  }

  private void overrideUnlessCurrent(RunningTurn turn, EndReason claimed) {
    EndReason current = turn.conversation().endReason;
    if (claimed != current) {
      override(turn, "end_reason", Map.of("model", claimed, "code", current));
    }
  }

  /** A fact once known stays known: UNKNOWN or null in the new decision never erases a value. */
  private String merged(String storedJson, Decision.Extracted extracted) {
    ObjectNode merged =
        storedJson == null ? json.createObjectNode() : (ObjectNode) json.readTree(storedJson);
    ObjectNode incoming = json.valueToTree(extracted);
    for (Map.Entry<String, JsonNode> fact : incoming.properties()) {
      if (isKnown(fact.getValue())) {
        merged.set(fact.getKey(), fact.getValue());
      }
    }
    return json.writeValueAsString(merged);
  }

  private static boolean isKnown(JsonNode value) {
    return !value.isNull() && !(value.isString() && value.stringValue().equals("UNKNOWN"));
  }

  /** {@code modelAndCode} holds what the model said and what code kept, for the inspector. */
  private void override(RunningTurn turn, String field, Map<String, ?> modelAndCode) {
    trace.record(turn, TraceLog.Entry.guard(field, TraceOutcome.OVERRIDE, modelAndCode));
  }
}
