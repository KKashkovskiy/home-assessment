package com.enrola.anna.scheduling;

import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model's function calls, by the names in {@code tools.json}. get_availability runs {@link
 * AvailabilityTool}; book_call books nothing and answers NOT_AVAILABLE_YET, so the model is never
 * told a booking happened. Arguments that are not the declared JSON are BAD_ARGS, and a tool that
 * breaks is TOOL_FAILED, so a call always gets an answer and the turn goes on. {@link #modelJson}
 * renders any result as the compact JSON the model reads.
 */
@Component
public class SchedulingTools {

  private static final Logger log = LoggerFactory.getLogger(SchedulingTools.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final ToolResult.Failure BAD_ARGS =
      new ToolResult.Failure("BAD_ARGS", "arguments must be the JSON the tool declares");
  private static final ToolResult.Failure NOT_AVAILABLE_YET =
      new ToolResult.Failure("NOT_AVAILABLE_YET", "nothing was booked; don't retry");
  private static final ToolResult.Failure UNKNOWN_TOOL =
      new ToolResult.Failure("UNKNOWN_TOOL", "the tools are get_availability and book_call");
  private static final ToolResult.Failure TOOL_FAILED =
      new ToolResult.Failure("TOOL_FAILED", "the calendar could not answer; don't retry");

  private final AvailabilityTool availability;

  public SchedulingTools(AvailabilityTool availability) {
    this.availability = availability;
  }

  /**
   * Runs the tool {@code name} for a lead in {@code leadZone}. Never throws: a bad call or a broken
   * tool is a failure result, which the caller counts like any other tool error.
   */
  public ToolResult execute(String name, String argumentsJson, ZoneId leadZone) {
    try {
      return switch (name) {
        case "get_availability" -> availability(argumentsJson, leadZone);
        case "book_call" -> NOT_AVAILABLE_YET;
        default -> UNKNOWN_TOOL;
      };
    } catch (RuntimeException e) { // a bug in a tool must cost the model one answer, not the turn
      log.warn("tool {} failed", name, e);
      return TOOL_FAILED;
    }
  }

  /**
   * Slot ids and lead-local labels, never a start time or offset, or a status, code and hint:
   * {@code {"timezone":"AEST","slots":[{"slot_id":"s...","local_label":"Mon 27 Jul 12:00 PM
   * AEST"}],"note":null}} or {@code {"status":"ERROR","code":"BAD_DATE","hint":"..."}}.
   */
  public static String modelJson(ToolResult result) {
    return JSON.writeValueAsString(
        switch (result) {
          case ToolResult.Slots slots ->
              new SlotsJson(
                  slots.timezone(),
                  slots.slots().stream()
                      .map(slot -> new SlotJson(slot.slotId(), slot.label()))
                      .toList(),
                  slots.note());
          case ToolResult.Failure failure ->
              new FailureJson("ERROR", failure.code(), failure.hint());
        });
  }

  private ToolResult availability(String argumentsJson, ZoneId leadZone) {
    AvailabilityTool.Args args;
    try {
      args = JSON.readValue(argumentsJson, AvailabilityTool.Args.class);
    } catch (JacksonException _) {
      return BAD_ARGS;
    }
    return args == null ? BAD_ARGS : availability.run(args, leadZone);
  }

  private record SlotsJson(String timezone, List<SlotJson> slots, ToolResult.Note note) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  private record SlotJson(String slotId, String localLabel) {}

  private record FailureJson(String status, String code, String hint) {}
}
