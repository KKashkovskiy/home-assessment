package com.enrola.anna.conversation;

import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.scheduling.SchedulingTools;
import com.enrola.anna.scheduling.ToolContext;
import com.enrola.anna.scheduling.ToolResult;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Answers the model's function calls for a running turn through {@link SchedulingTools}, for its
 * lead in the lead's zone, and keeps what they leave on the conversation: an availability result
 * replaces the last offer, a booking meets the goal, a booking the calendar made but no row records
 * hands off with ERROR, and a calendar or tool failure adds to a streak that an answer resets and
 * whose second failure hands off with ERROR. A call refused as the model's own mistake goes back to
 * it and leaves the streak as it was; the tool rounds bound those. Every call leaves a TOOL_CALL
 * event with what the model sent and read and, for availability, how the slots were filtered.
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
    ToolResult result = tools.execute(name, argumentsJson, contextOf(turn));
    keep(turn, result);
    String answer = SchedulingTools.modelJson(result);
    trace.record(
        turn,
        new TraceLog.Entry(
            TraceEventType.TOOL_CALL,
            name,
            outcomeOf(result),
            new ToolCallPayload(
                argumentsJson,
                answer,
                slotCount(result),
                filteringOf(result),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))));
    return answer;
  }

  private static ToolContext contextOf(RunningTurn turn) {
    Conversation conversation = turn.conversation();
    return new ToolContext(
        conversation.id,
        turn.lead(),
        ZoneId.of(conversation.leadZoneId),
        conversation.lastOfferedSlots(),
        turn.settings().scheduling());
  }

  private static void keep(RunningTurn turn, ToolResult result) {
    Conversation conversation = turn.conversation();
    switch (result) {
      case ToolResult.Slots slots -> {
        conversation.setLastOfferedSlots(slots.slots());
        conversation.upstreamErrors = 0;
      }
      case ToolResult.Booked _ -> {
        StateRules.booked(conversation, turn.now());
        conversation.upstreamErrors = 0;
      }
      case ToolResult.Failure _ -> {
        conversation.upstreamErrors++;
        if (conversation.upstreamErrors >= ERRORS_BEFORE_HANDOFF) {
          StateRules.handoff(conversation, HandoffReason.ERROR);
        }
      }
      case ToolResult.BookingUnknown _ -> StateRules.handoff(conversation, HandoffReason.ERROR);
      case ToolResult.Rejected _ -> {}
    }
  }

  private static TraceOutcome outcomeOf(ToolResult result) {
    return switch (result) {
      case ToolResult.Slots _, ToolResult.Booked _ -> TraceOutcome.PASS;
      case ToolResult.Failure _, ToolResult.Rejected _, ToolResult.BookingUnknown _ ->
          TraceOutcome.BLOCK;
    };
  }

  private static ToolResult.Filtering filteringOf(ToolResult result) {
    return switch (result) {
      case ToolResult.Slots slots -> slots.filtering();
      case ToolResult.Booked _,
          ToolResult.Failure _,
          ToolResult.Rejected _,
          ToolResult.BookingUnknown _ ->
          null;
    };
  }

  private static int slotCount(ToolResult result) {
    return switch (result) {
      case ToolResult.Slots slots -> slots.slots().size();
      case ToolResult.Booked _,
          ToolResult.Failure _,
          ToolResult.Rejected _,
          ToolResult.BookingUnknown _ ->
          0;
    };
  }
}
