package com.enrola.anna.guard;

import com.enrola.anna.agent.OpenAiClientConfig;
import com.enrola.anna.config.SettingsDefaults;
import com.openai.client.OpenAIClient;
import com.openai.models.moderations.Moderation;
import com.openai.models.moderations.ModerationCreateParams;
import com.openai.models.moderations.ModerationModel;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * OpenAI's moderations endpoint over the shared client, with its own timeout and retries from
 * {@code anna.moderation}, read once here; exists only when {@code OPENAI_API_KEY} is set. A call
 * that fails in any way, an answer with no result included, is {@link
 * ModerationVerdict#UNAVAILABLE}: moderation being down must never lose or block a turn, and the
 * model's own ABUSIVE and SELF_HARM intents still protect the conversation.
 */
@Component
@ConditionalOnExpression(OpenAiClientConfig.WHEN_KEY_SET)
public final class OpenAiModerationClient implements ModerationClient {

  private static final Logger log = LoggerFactory.getLogger(OpenAiModerationClient.class);

  private final OpenAIClient client;

  public OpenAiModerationClient(OpenAIClient client, SettingsDefaults defaults) {
    SettingsDefaults.Moderation moderation = defaults.moderation();
    this.client =
        client.withOptions(
            options -> options.timeout(moderation.timeout()).maxRetries(moderation.maxRetries()));
  }

  @Override
  public ModerationVerdict screen(String text) {
    ModerationCreateParams params =
        ModerationCreateParams.builder()
            .input(text)
            .model(ModerationModel.OMNI_MODERATION_LATEST)
            .build();
    try {
      return verdict(client.moderations().create(params).results());
    } catch (RuntimeException e) { // any failure, the SDK's or not, must leave the turn running
      log.warn("moderation unavailable: {}", e.getClass().getSimpleName());
      return ModerationVerdict.UNAVAILABLE;
    }
  }

  /** The scores of the first result, the one input's; with no result this throws. */
  private static ModerationVerdict verdict(List<Moderation> results) {
    Moderation.CategoryScores scores = results.getFirst().categoryScores();
    return new ModerationVerdict(
        Math.max(scores.harassment(), scores.harassmentThreatening()),
        Math.max(scores.violence(), scores.violenceGraphic()),
        Math.max(
            scores.selfHarm(), Math.max(scores.selfHarmIntent(), scores.selfHarmInstructions())),
        true);
  }
}
