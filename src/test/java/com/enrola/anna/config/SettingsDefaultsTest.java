package com.enrola.anna.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The yml binder: a fixed clock's instant becomes advisor-local time, and the client wiring no save
 * can change fails startup when it is missing.
 */
class SettingsDefaultsTest {

  @EnableConfigurationProperties(SettingsDefaults.class)
  static class Defaults {}

  /** Midnight UTC is 10:00 in Melbourne in July. */
  @Test
  void fixedInstantBecomesAdvisorLocalTime() {
    Settings settings =
        SettingsFixture.yml("anna.clock.mode=FIXED", "anna.clock.fixed-at=2026-07-26T00:00:00Z")
            .settings();

    assertThat(settings.clock())
        .isEqualTo(
            new Settings.Clock(Settings.ClockMode.FIXED, LocalDateTime.of(2026, 7, 26, 10, 0)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"agent.timeout", "moderation.timeout"})
  void missingClientWiringFailsStartup(String field) {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Defaults.class)
        .withPropertyValues("anna." + field + "=")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("field '" + field + "'"));
  }
}
