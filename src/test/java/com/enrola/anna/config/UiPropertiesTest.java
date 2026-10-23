package com.enrola.anna.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** A live-log stream that times out at once would reconnect forever, so it fails startup. */
class UiPropertiesTest {

  @EnableConfigurationProperties(UiProperties.class)
  static class Ui {}

  final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(Ui.class)
          .withPropertyValues("anna.ui.sse-enabled=true", "anna.ui.sse-timeout-min=1");

  @Test
  void oneMinuteStarts() {
    runner.run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void zeroMinutesFailsStartup() {
    runner
        .withPropertyValues("anna.ui.sse-timeout-min=0")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("field 'sseTimeoutMin'"));
  }
}
