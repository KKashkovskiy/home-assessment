package com.enrola.anna.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** A tool-round cap of zero would fail every turn that needs a calendar, so it fails startup. */
class AgentPropertiesTest {

  @EnableConfigurationProperties(AgentProperties.class)
  static class Agent {}

  final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(Agent.class)
          .withPropertyValues("anna.agent.model=gpt-5.1", "anna.agent.max-tool-rounds=1");

  @Test
  void oneToolRoundStarts() {
    runner.run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void noToolRoundsFailsStartup() {
    runner
        .withPropertyValues("anna.agent.max-tool-rounds=0")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("field 'maxToolRounds'"));
  }
}
