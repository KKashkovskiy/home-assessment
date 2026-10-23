package com.enrola.anna.eval;

import com.enrola.anna.agent.ModelClient;
import com.enrola.anna.agent.ModelRequest;
import com.enrola.anna.agent.ModelResponse;
import java.util.Objects;

/**
 * The model of an offline run, in place of OpenAI: it answers with the stand-in a runner armed it
 * with last, a {@link StandInModel} for a turn trial or a {@link StoryStandIn} for one story turn.
 */
final class OfflineModel implements ModelClient {

  private volatile ModelClient armed;

  /** Set before each turn the product runs. */
  void arm(ModelClient standIn) {
    armed = standIn;
  }

  @Override
  public ModelResponse call(ModelRequest request) {
    return Objects.requireNonNull(armed, "arm() first").call(request);
  }
}
