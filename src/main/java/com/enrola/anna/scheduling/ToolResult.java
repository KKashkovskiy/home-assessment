package com.enrola.anna.scheduling;

import java.util.List;

/** What a tool answers the model; {@link SchedulingTools} renders it as compact JSON. */
public sealed interface ToolResult {

  /**
   * Up to {@code max-slots} lead-labelled slots; {@code note} is null when there is nothing to say.
   */
  record Slots(String timezone, List<Slot> slots, Note note) implements ToolResult {}

  /** The booked call: the calendar's event id and the lead-local label of its start. */
  record Booked(String bookingId, String label) implements ToolResult {}

  /**
   * The calendar refused or the tool broke: a compact code and a one-line hint for the model, never
   * a stack trace. These add up toward a handoff.
   */
  record Failure(String code, String hint) implements ToolResult {}

  /**
   * The call was refused before the calendar was asked: not the declared arguments, no such date or
   * tool, a slot that was never offered, no phone to call. Answered like a {@link Failure}, but the
   * model's mistake, not the calendar's, so it adds to no handoff count.
   */
  record Rejected(String code, String hint) implements ToolResult {}

  /**
   * The calendar accepted a booking the database did not keep: whether the call is booked is
   * unknown, so a human checks before anyone retries.
   */
  record BookingUnknown(String hint) implements ToolResult {}

  /** What the model is told about an availability window: a closed set the prompt can rely on. */
  enum Note {
    /** The window starts past the horizon: nothing can be offered there. */
    BEYOND_HORIZON,
    /** The window runs past the horizon and was cut at it. */
    CLAMPED_TO_HORIZON,
    /** Nothing free in the window. */
    NO_SLOTS_IN_WINDOW
  }
}
