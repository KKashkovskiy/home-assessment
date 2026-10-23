package com.enrola.anna.conversation;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.HandoffReason;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one place a model decision changes a conversation: stage, flags, extracted facts, summary,
 * and the ending or handoff it asks for, through {@link StateRules}. What to send is the caller's
 * business. The model's {@code goal_met} is advisory: a claim is traced as overridden and changes
 * nothing, because code owns the goal.
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

  void apply(Conversation conversation, int turnNo, Decision decision, Instant now) {
    conversation.stage = decision.stage();
    decision.flags().forEach(conversation::addFlag);
    conversation.extractedJson = merged(conversation.extractedJson, decision.extracted());
    if (decision.summary() != null) {
      conversation.summary = AgentTurn.cut(decision.summary(), SUMMARY_MAX);
    }
    if (decision.conversationEnded() && decision.endReason() != EndReason.NONE) {
      StateRules.end(conversation, decision.endReason(), now);
    }
    if (decision.needsHuman() && decision.handoffReason() != HandoffReason.NONE) {
      StateRules.handoff(conversation, decision.handoffReason());
    }
    if (decision.goalMet()) {
      traceGoalMetOverride(conversation, turnNo);
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

  private void traceGoalMetOverride(Conversation conversation, int turnNo) {
    trace.record(
        conversation.id,
        turnNo,
        new TraceLog.Entry(
            TraceEventType.GUARD,
            "goal_met_override",
            TraceOutcome.OVERRIDE,
            Map.of("model", true, "code", false)));
  }
}
