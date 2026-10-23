package com.enrola.anna.eval;

import java.math.BigDecimal;
import java.util.List;

/**
 * One turn of a story as its page shows it: the lead's line, Anna's replies, the turn's tool calls
 * as name and payload JSON, what the turn cost with the simulated lead's line, and the {@link
 * Simulator.Moment} it was sent at: after the end it is a probe that Anna stays quiet. Turn 0 is
 * the opener, with no lead line. The turn's checks are the trial's checks about {@code turnNo}.
 */
record StoryTurn(
    int turnNo,
    String lead,
    List<String> replies,
    List<String> tools,
    BigDecimal costUsd,
    Simulator.Moment moment) {

  boolean probe() {
    return moment == Simulator.Moment.AFTER_END;
  }
}
