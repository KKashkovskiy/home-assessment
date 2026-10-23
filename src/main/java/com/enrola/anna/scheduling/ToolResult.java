package com.enrola.anna.scheduling;

import java.util.List;

/** What a tool answers the model; {@link SchedulingTools} renders it as compact JSON. */
public sealed interface ToolResult {

  /**
   * Up to {@code max-slots} lead-labelled slots; {@code note} is null when there is nothing to say.
   */
  record Slots(String timezone, List<Slot> slots, Note note) implements ToolResult {}

  /** A compact code and a one-line hint for the model, never a stack trace. */
  record Failure(String code, String hint) implements ToolResult {}

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
