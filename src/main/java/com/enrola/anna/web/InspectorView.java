package com.enrola.anna.web;

import com.enrola.anna.conversation.ModelRoundPayload;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

/**
 * The inspector pane of the open conversation: every turn, oldest first, whether the session shows
 * the raw decision JSON, and whether the live log runs. The footer's totals add up the turns.
 */
public record InspectorView(List<TurnView> turns, boolean rawOpen, boolean live) {

  /** No turn yet: nothing to expand, collapse or total. */
  public boolean empty() {
    return turns.isEmpty();
  }

  /** Only the latest turn opens on load. */
  public boolean opensOnLoad(TurnView turn) {
    return turn.n() == turns.getLast().n();
  }

  public BigDecimal cost() {
    return turns.stream().map(TurnView::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  public long modelTimeMs() {
    return turns.stream().mapToLong(TurnView::latencyMs).sum();
  }

  /** Round trips to the model, tool rounds and repairs included. */
  public long modelCalls() {
    return turns.stream().flatMap(InspectorView::modelRows).count();
  }

  /** Turns whose first reply failed the guards and went back to the model. */
  public long repairs() {
    return turns.stream()
        .filter(
            turn -> modelRows(turn).anyMatch(row -> row.call() == ModelRoundPayload.Call.REPAIR))
        .count();
  }

  private static Stream<TurnView.ModelRow> modelRows(TurnView turn) {
    return turn.steps().stream()
        .filter(TurnView.ModelRow.class::isInstance)
        .map(TurnView.ModelRow.class::cast);
  }

  /**
   * The prompt dialog: the instructions a turn's request carried and the context sent with them,
   * or, without a turn, the pinned version's stored prompt and no context. {@code turnNo} is null
   * for the pinned version and {@code instructions} null when that version is no longer stored.
   */
  public record Prompt(String version, Integer turnNo, String instructions, String context) {

    /** As one turn's request sent it, rather than as the pinned version stores it. */
    public boolean sent() {
      return turnNo != null;
    }
  }
}
