package com.enrola.anna.conversation;

import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.scheduling.SchedulingTools;
import com.enrola.anna.scheduling.ToolResult;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Answers the model's function calls for a running turn, in the lead's zone, through {@link
 * SchedulingTools}, and keeps what they leave on the conversation: an availability result replaces
 * the last offer, and a tool error adds to a streak that an answer resets and whose second error
 * hands off with ERROR. Every call leaves a TOOL_CALL event with what the model sent and read.
 */
@Component
class TurnToolCalls {

  private static final int ERRORS_BEFORE_HANDOFF = 2;

  private final SchedulingTools tools;
  private final TraceLog trace;

  TurnToolCalls(SchedulingTools tools, TraceLog trace) {
    this.tools = tools;
    this.trace = trace;
  }

  /** The JSON the model reads for its call of {@code name} with {@code argumentsJson}. */
  String answer(RunningTurn turn, String name, String argumentsJson) {
    long start = System.nanoTime();
    Conversation conversation = turn.conversation();
    ToolResult result = tools.execute(name, argumentsJson, ZoneId.of(conversation.leadZoneId));
    keep(conversation, result);
    String answer = SchedulingTools.modelJson(result);
    trace.record(
        turn,
        new TraceLog.Entry(
            TraceEventType.TOOL_CALL,
            name,
            result instanceof ToolResult.Failure ? TraceOutcome.BLOCK : TraceOutcome.PASS,
            Map.of(
                "args",
                argumentsJson,
                "result",
                answer,
                "slot_count",
                slotCount(result),
                "latency_ms",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))));
    return answer;
  }

  private static void keep(Conversation conversation, ToolResult result) {
    switch (result) {
      case ToolResult.Slots slots -> {
        conversation.setLastOfferedSlots(slots.slots());
        conversation.upstreamErrors = 0;
      }
      case ToolResult.Failure _ -> {
        conversation.upstreamErrors++;
        if (conversation.upstreamErrors >= ERRORS_BEFORE_HANDOFF) {
          StateRules.handoff(conversation, HandoffReason.ERROR);
        }
      }
    }
  }

  private static int slotCount(ToolResult result) {
    return switch (result) {
      case ToolResult.Slots slots -> slots.slots().size();
      case ToolResult.Failure _ -> 0;
    };
  }
}
