package com.enrola.anna.eval;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The harness on top of the product context: the seeder, the reader, the runner and the run
 * factory, and with {@code eval.offline=true} the stand-in model and moderation in place of OpenAI.
 * A keyed run uses the product's own OpenAI beans on the key {@link EvalKey} names, which the eval
 * context sets as {@code OPENAI_API_KEY}.
 */
@TestConfiguration
@Import({Seeder.class, TurnRowsReader.class, TurnRunner.class, EvalRuns.class})
class EvalConfig {

  @Bean
  @ConditionalOnProperty(name = "eval.offline", havingValue = "true")
  StandInModel standInModel() {
    return new StandInModel();
  }

  @Bean
  @ConditionalOnProperty(name = "eval.offline", havingValue = "true")
  ScenarioModeration scenarioModeration() {
    return new ScenarioModeration();
  }
}
