package com.enrola.anna.conversation;

import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.guard.PreGuards;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The payload of the GUARD row that reads the model's answer after a hard end, when moderation did
 * not answer and no self-harm phrase matched: the detector, and the intent the model read or, when
 * the call failed, the failure; the other is null and left out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SelfHarmCheckPayload(
    PreGuards.Detector detector, LeadIntent leadIntent, ModelResponse.Failure failure) {

  static SelfHarmCheckPayload read(LeadIntent intent) {
    return new SelfHarmCheckPayload(PreGuards.Detector.MODEL_CLASSIFY, intent, null);
  }

  static SelfHarmCheckPayload failed(ModelResponse.Failure failure) {
    return new SelfHarmCheckPayload(PreGuards.Detector.MODEL_CLASSIFY, null, failure);
  }
}
