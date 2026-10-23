package com.enrola.anna.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The limits, budgets and moderation settings the guards read: a missing or out-of-range value
 * fails startup instead of reaching a lead, where a zero threshold would send every lead the safety
 * template.
 */
class GuardPropertiesTest {

  @EnableConfigurationProperties({
    LimitsProperties.class,
    BudgetsProperties.class,
    ModerationProperties.class
  })
  static class Guards {}

  static final String[] VALID = {
    "anna.limits.max-turns=15",
    "anna.limits.off-topic-limit=3",
    "anna.limits.objection-retries=0",
    "anna.budgets.app-cap-usd=10",
    "anna.budgets.conversation-cap-usd=0",
    "anna.moderation.harassment=1",
    "anna.moderation.threat=0.5",
    "anna.moderation.self-harm=0.3",
    "anna.moderation.timeout=5s",
    "anna.moderation.max-retries=0"
  };

  final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Guards.class).withPropertyValues(VALID);

  @Test
  void boundaryValuesStart() {
    runner.run(context -> assertThat(context).hasNotFailed());
  }

  /** A value missing from the binding is reported by field, a rejected one by property too. */
  @ParameterizedTest(name = "{0}={1}")
  @CsvSource({
    "anna.limits.max-turns,             0,     maxTurns",
    "anna.limits.off-topic-limit,       0,     offTopicLimit",
    "anna.limits.objection-retries,     -1,    objectionRetries",
    "anna.budgets.app-cap-usd,          '',    appCapUsd",
    "anna.budgets.conversation-cap-usd, -0.01, conversationCapUsd",
    "anna.moderation.harassment,        0,     harassment",
    "anna.moderation.threat,            1.01,  threat",
    "anna.moderation.self-harm,         0,     selfHarm",
    "anna.moderation.timeout,           '',    timeout",
    "anna.moderation.max-retries,       -1,    maxRetries"
  })
  void missingOrOutOfRangeValueFailsStartup(String property, String value, String field) {
    runner
        .withPropertyValues(property + "=" + value)
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("field '" + field + "'"));
  }
}
