package com.enrola.anna.conversation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The conversation fields a turn may change, by their wire names, taken before and after the turn
 * for its STATE_DIFF event.
 */
record StateSnapshot(Map<String, Object> fields) {

  /** One field's value before and after the turn. */
  record Change(Object from, Object to) {}

  static StateSnapshot of(Conversation conversation) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("status", conversation.status);
    fields.put("stage", conversation.stage);
    fields.put("flags", conversation.flags());
    fields.put("goal_met", conversation.goalMet);
    fields.put("unsubscribed", conversation.unsubscribed);
    fields.put("ended", conversation.ended);
    fields.put("end_reason", conversation.endReason);
    fields.put("needs_human", conversation.needsHuman);
    fields.put("handoff_reason", conversation.handoffReason);
    fields.put("objections_used", conversation.objectionsUsed);
    fields.put("off_topic_streak", conversation.offTopicStreak);
    fields.put("agent_turns", conversation.agentTurns);
    fields.put("post_terminal_replies", conversation.postTerminalReplies);
    fields.put("upstream_errors", conversation.upstreamErrors);
    return new StateSnapshot(fields);
  }

  /** The fields that differ in {@code after}, in snapshot order; empty when none do. */
  Map<String, Change> changesTo(StateSnapshot after) {
    Map<String, Change> changes = new LinkedHashMap<>();
    fields.forEach(
        (field, from) -> {
          Object to = after.fields.get(field);
          if (!Objects.equals(from, to)) {
            changes.put(field, new Change(from, to));
          }
        });
    return changes;
  }
}
