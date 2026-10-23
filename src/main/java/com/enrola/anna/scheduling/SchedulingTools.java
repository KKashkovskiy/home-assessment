package com.enrola.anna.scheduling;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model's function calls, by the names in {@code tools.json}: get_availability runs {@link
 * AvailabilityTool} and book_call {@link BookCallTool}. Arguments that are not the declared JSON
 * are BAD_ARGS, and a tool that breaks is TOOL_FAILED, so a call always gets an answer and the turn
 * goes on. {@link #modelJson} renders any result as the compact JSON the model reads, and {@link
 * #summary} reads that JSON back as one line for the console.
 */
@Component
public class SchedulingTools {

  private static final Logger log = LoggerFactory.getLogger(SchedulingTools.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final ToolResult.Rejected BAD_ARGS =
      new ToolResult.Rejected("BAD_ARGS", "arguments must be the JSON the tool declares");
  private static final ToolResult.Rejected UNKNOWN_TOOL =
      new ToolResult.Rejected("UNKNOWN_TOOL", "the tools are get_availability and book_call");
  private static final ToolResult.Failure TOOL_FAILED =
      new ToolResult.Failure("TOOL_FAILED", "the calendar could not answer; don't retry");

  private final AvailabilityTool availability;
  private final BookCallTool bookCall;

  public SchedulingTools(AvailabilityTool availability, BookCallTool bookCall) {
    this.availability = availability;
    this.bookCall = bookCall;
  }

  /**
   * Runs the tool {@code name} for the conversation {@code context} describes. Never throws: a bad
   * call is a rejection and a broken tool a failure, each answered with a code.
   */
  public ToolResult execute(String name, String argumentsJson, ToolContext context) {
    try {
      return switch (name) {
        case "get_availability" ->
            args(argumentsJson, AvailabilityTool.Args.class)
                .map(args -> availability.run(args, context.leadZone()))
                .orElse(BAD_ARGS);
        case "book_call" ->
            args(argumentsJson, BookCallTool.Args.class)
                .map(args -> bookCall.run(args, context))
                .orElse(BAD_ARGS);
        default -> UNKNOWN_TOOL;
      };
    } catch (RuntimeException e) { // a bug in a tool must cost the model one answer, not the turn
      log.warn("tool {} failed", name, e);
      return TOOL_FAILED;
    }
  }

  /**
   * Slot ids and lead-local labels, never a start time or offset; a booking; or a status, code and
   * hint: {@code {"timezone":"AEST","slots":[{"slot_id":"s...","local_label":"Mon 27 Jul 12:00 PM
   * AEST"}],"note":null}}, {@code {"status":"BOOKED","booking_id":"cal-...","label":"Mon 27 Jul
   * 12:00 PM AEST"}} or {@code {"status":"ERROR","code":"BAD_DATE","hint":"..."}}.
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
          case ToolResult.Booked booked ->
              new BookedJson("BOOKED", booked.bookingId(), booked.label());
          case ToolResult.Failure failure ->
              new FailureJson("ERROR", failure.code(), failure.hint());
          case ToolResult.Rejected rejected ->
              new FailureJson("ERROR", rejected.code(), rejected.hint());
          case ToolResult.BookingUnknown unknown ->
              new FailureJson("ERROR", "UNKNOWN_BOOKING", unknown.hint());
        });
  }

  /**
   * One line for an answer {@link #modelJson} wrote: "3 slots", "3 slots, CLAMPED_TO_HORIZON",
   * "booked Mon 27 Jul 12:00 PM AEST", or the error code.
   */
  public static String summary(String modelJson) {
    return switch (JSON.readValue(modelJson, Answer.class)) {
      case SlotsJson slots ->
          slots.slots().size()
              + (slots.slots().size() == 1 ? " slot" : " slots")
              + (slots.note() == null ? "" : ", " + slots.note());
      case BookedJson booked -> "booked " + booked.label();
      case FailureJson failure -> failure.code();
    };
  }

  /** The arguments as {@code type}, or empty when they are not that JSON or are {@code null}. */
  private static <T> Optional<T> args(String argumentsJson, Class<T> type) {
    try {
      return Optional.ofNullable(JSON.readValue(argumentsJson, type));
    } catch (JacksonException _) {
      return Optional.empty();
    }
  }

  /** The shapes of an answer, told apart on reading by the fields each has. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
  @JsonSubTypes({
    @JsonSubTypes.Type(SlotsJson.class),
    @JsonSubTypes.Type(BookedJson.class),
    @JsonSubTypes.Type(FailureJson.class)
  })
  private sealed interface Answer permits SlotsJson, BookedJson, FailureJson {}

  private record SlotsJson(String timezone, List<SlotJson> slots, ToolResult.Note note)
      implements Answer {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  private record SlotJson(String slotId, String localLabel) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  private record BookedJson(String status, String bookingId, String label) implements Answer {}

  private record FailureJson(String status, String code, String hint) implements Answer {}
}
