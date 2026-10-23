package com.enrola.anna.eval;

import com.enrola.anna.agent.OpenAiClientConfig;
import com.enrola.anna.agent.OpenAiModelClient;
import com.openai.client.OpenAIClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The harness on top of the product context: the seeder, the reader, the runners, the eval's own
 * models and the run factory. With {@code eval.offline=true} the offline model, the moderation and
 * the scripted lead stand in for OpenAI. A keyed run uses the product's own OpenAI beans on the key
 * {@link EvalKey} names, which the eval context sets as {@code OPENAI_API_KEY}, and plays the lead
 * and asks the judge on that key, each through a client of its own.
 */
@TestConfiguration
@Import({
  Seeder.class,
  TurnRowsReader.class,
  TurnRunner.class,
  StoryRunner.class,
  EvalModels.class,
  EvalRuns.class
})
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

  /** The simulated lead of a keyed run; its model must be priced, or the run is refused. */
  @Bean
  @ConditionalOnExpression(OpenAiClientConfig.WHEN_KEY_SET)
  LeadSimulator leadSimulator(OpenAIClient client, EvalModels models) {
    return new LeadSimulator(
        new OpenAiModelClient(client), models.simulator(), LeadSimulator.Rules.fromFiles());
  }

  /** The judge of a keyed run; an unpriced judge model is never asked, see {@link JudgeMode}. */
  @Bean
  @ConditionalOnExpression(OpenAiClientConfig.WHEN_KEY_SET)
  JudgeClient judgeClient(OpenAIClient client, EvalModels models) {
    return new JudgeClient(
        new OpenAiModelClient(client), models.judge(), JudgeClient.Rules.fromFiles());
  }
}
