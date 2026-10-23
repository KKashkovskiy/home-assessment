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
 * is a warning, or a failed boot when {@code anna.agent.strict-model-check} is true; {@link
 * #result()} tells the console how it went. Present only with a key, like {@link
 * OpenAiModelClient}; without one {@link AgentService} logs the skip.
 */
@Component
@ConditionalOnExpression(OpenAiClientConfig.WHEN_KEY_SET)
public class ModelCheck implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ModelCheck.class);

  /** How the check went; PENDING until it has run, for the first seconds after boot. */
  public enum Result {
    PENDING,
    PASSED,
    FAILED
  }

  private final ModelClient client;
  private final AgentProperties agent;
  private volatile Result result = Result.PENDING;

  public ModelCheck(ModelClient client, AgentProperties agent) {
    this.client = client;
    this.agent = agent;
  }

  @Override
  public void run(ApplicationArguments args) {
    switch (client.call(ModelRequest.ping(agent.model()))) {
      case ModelResponse.Decided _ -> passed();
      case ModelResponse.Failed failed -> failed(failed.code().name());
      case ModelResponse.ToolCalls _ -> failed("called a tool none was offered");
    }
  }

  public Result result() {
    return result;
  }

  private void passed() {
    result = Result.PASSED;
    log.info("model check ok: {}", agent.model());
  }

  private void failed(String why) {
    result = Result.FAILED;
    if (agent.strictModelCheck()) {
      throw new IllegalStateException("model check failed for " + agent.model() + ": " + why);
    }
    log.warn("model check failed for {}: {}", agent.model(), why);
  }
}
