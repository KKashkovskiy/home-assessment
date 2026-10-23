package com.enrola.anna.eval;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.eval.Scenario.Expect;
import com.enrola.anna.guard.ModerationClient;
import com.enrola.anna.guard.ModerationVerdict;
import java.util.Map;

/**
 * The offline moderation endpoint. It answers with the scores the scenario's {@code
 * config.moderation} names; without them it derives them from {@code expect} as the stand-in model
 * derives its decision: an expected ABUSIVE end scores harassment, an expected SAFETY end or
 * SELF_HARM handoff scores self-harm, anything else is clean. A keyed run screens for real.
 */
final class ScenarioModeration implements ModerationClient {

  private volatile Map<String, Double> scores = Map.of();

  /** Set before each trial; a story's scores hold for each of its lines. */
  void arm(Scenario scenario) {
    scores =
        scenario.config().moderation() != null
            ? scenario.config().moderation()
            : derived(scenario.expect());
  }

  @Override
  public ModerationVerdict screen(String text) {
    return new ModerationVerdict(score("harassment"), score("threat"), score("self_harm"), true);
  }

  private static Map<String, Double> derived(Expect expect) {
    if (expect.endReason() == EndReason.ABUSIVE) {
      return Map.of("harassment", 1.0);
    }
    if (expect.endReason() == EndReason.SAFETY
        || expect.handoffReason() == HandoffReason.SELF_HARM) {
      return Map.of("self_harm", 1.0);
    }
    return Map.of();
  }

  private double score(String category) {
    return scores.getOrDefault(category, 0.0);
  }
}
