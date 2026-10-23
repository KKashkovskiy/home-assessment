package com.enrola.anna.agent;

import com.enrola.anna.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Boot-time check that the configured model answers: one strict request of a few tokens. A failure
 * is a warning, or a failed boot when {@code anna.agent.strict-model-check} is true. Present only
 * with a key, like {@link OpenAiModelClient}; without one {@link AgentService} logs the skip.
 */
@Component
@ConditionalOnExpression(OpenAiClientConfig.WHEN_KEY_SET)
public class ModelCheck implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ModelCheck.class);

  private final ModelClient client;
  private final AgentProperties agent;

  public ModelCheck(ModelClient client, AgentProperties agent) {
    this.client = client;
    this.agent = agent;
  }

  @Override
  public void run(ApplicationArguments args) {
    switch (client.call(ModelRequest.ping(agent.model()))) {
      case ModelResponse.Decided _ -> log.info("model check ok: {}", agent.model());
      case ModelResponse.Failed failed when agent.strictModelCheck() ->
          throw new IllegalStateException(
              "model check failed for " + agent.model() + ": " + failed.code());
      case ModelResponse.Failed failed ->
          log.warn("model check failed for {}: {}", agent.model(), failed.code());
    }
  }
}
