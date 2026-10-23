package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** get_availability arguments read from the lead's words, on the seed set's Sunday. */
class StandInDecisionsTest {

  static final LocalDate SUNDAY = ScenarioFixtures.SUNDAY.toLocalDate();

  @Test
  void availabilityFollowsTheNamedDayAndPart() {
    assertThat(StandInDecisions.availabilityArgs("Thursday's better", SUNDAY))
        .containsEntry("date_from", "2026-07-30")
        .containsEntry("date_to", "2026-07-30")
        .containsEntry("part_of_day", "ANY");
    assertThat(StandInDecisions.availabilityArgs("sometime arvo", SUNDAY))
        .containsEntry("date_from", "2026-07-27")
        .containsEntry("date_to", "2026-08-02")
        .containsEntry("part_of_day", "AFTERNOON");
  }
}
