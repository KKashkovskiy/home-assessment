package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.conversation.Source;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The range's lower bound off the app clock, and the same cohort as a list filter. */
class AnalyticsFilterTest {

  static final Instant NOW = Instant.parse("2026-07-26T00:00:30.250Z");

  @ParameterizedTest
  @CsvSource(
      nullValues = "none",
      textBlock =
          """
          LAST_24H | 2026-07-25T00:00:00Z
          LAST_7D  | 2026-07-19T00:00:00Z
          LAST_30D | 2026-06-26T00:00:00Z
          ALL      | none
          """,
      delimiter = '|')
  void sinceIsTheRangeBackFromNowToTheMinute(AnalyticsFilter.Range range, Instant since) {
    assertThat(new AnalyticsFilter(range, null, null, null).since(NOW)).isEqualTo(since);
  }

  @Test
  void missingRangeIsTheLastSevenDaysAndBlanksAreAll() {
    AnalyticsFilter filter = new AnalyticsFilter(null, null, " ", "");

    assertThat(filter)
        .isEqualTo(new AnalyticsFilter(AnalyticsFilter.Range.LAST_7D, null, null, null));
  }

  @Test
  void listFilterCarriesTheSameCohortAndLeavesResetsOut() {
    AnalyticsFilter filter =
        new AnalyticsFilter(AnalyticsFilter.Range.LAST_24H, Source.SIM, "gpt-5.1", "v2");

    assertThat(filter.toConversationFilter(NOW))
        .isEqualTo(
            ConversationFilter.cohort(
                Source.SIM, "v2", "gpt-5.1", Instant.parse("2026-07-25T00:00:00Z")));
    assertThat(filter.toConversationFilter(NOW).reset()).isFalse();
  }

  @Test
  void withPromptVersionKeepsTheRest() {
    AnalyticsFilter filter = new AnalyticsFilter(AnalyticsFilter.Range.ALL, Source.API, "m", "v1");

    assertThat(filter.withPromptVersion("v3"))
        .isEqualTo(new AnalyticsFilter(AnalyticsFilter.Range.ALL, Source.API, "m", "v3"));
  }
}
