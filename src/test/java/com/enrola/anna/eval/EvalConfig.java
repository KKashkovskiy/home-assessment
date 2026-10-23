package com.enrola.anna.eval;

import com.enrola.anna.agent.OpenAiClientConfig;
import com.enrola.anna.agent.OpenAiModelClient;
import com.enrola.anna.agent.PriceTable;
import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsStore;
import com.openai.client.OpenAIClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The harness on top of the product context: the seeder, the reader, the runners and the run
 * factory. With {@code eval.offline=true} the offline model, the moderation and the scripted lead
 * stand in for OpenAI. A keyed run uses the product's own OpenAI beans on the key {@link EvalKey}
 * names, which the eval context sets as {@code OPENAI_API_KEY}, and plays the lead on that key.
 */
@TestConfiguration
@Import({Seeder.class, TurnRowsReader.class, TurnRunner.class, StoryRunner.class, EvalRuns.class})
class EvalConfig {

  @Bean
  @ConditionalOnProperty(name = "eval.offline", havingValue = "true")
  OfflineModel offlineModel() {
    return new OfflineModel();
  }

  @Bean
  @ConditionalOnProperty(name = "eval.offline", havingValue = "true")
  ScenarioModeration scenarioModeration() {
    return new ScenarioModeration();
  }

  @Bean
  @ConditionalOnProperty(name = "eval.offline", havingValue = "true")
  ScriptedSimulator scriptedSimulator() {
    return new ScriptedSimulator();
  }

  /**
   * The simulated lead of a keyed run: a transport of its own over the eval-keyed client, never
   * Anna's model bean, so its spend is a line of its own. It runs on Anna's model unless {@code
   * eval.simulator.model} names a cheaper one, which the settings' price table must then price.
   */
  @Bean
  @ConditionalOnExpression(OpenAiClientConfig.WHEN_KEY_SET)
  LeadSimulator leadSimulator(
      OpenAIClient client,
      SettingsStore settings,
      @Value("${eval.simulator.model:}") String model) {
    Settings current = settings.current();
    return new LeadSimulator(
        new OpenAiModelClient(client),
        model.isBlank() ? current.agent().model() : model,
        new PriceTable(current.priceTable()),
        LeadSimulator.Rules.fromFiles());
  }
}
