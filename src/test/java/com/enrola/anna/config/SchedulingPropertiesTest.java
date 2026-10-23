package com.enrola.anna.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.config.SchedulingProperties.CalendarPreset;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The scheduling and clock settings as written in application.yml bind to their types, and a value
 * that would offer no slots, offer slots past the calendar's range or leave a fixed clock without a
 * time fails startup.
 */
class SchedulingPropertiesTest {

  @EnableConfigurationProperties({SchedulingProperties.class, ClockProperties.class})
  static class Scheduling {}

  static final String[] VALID = {
    "anna.scheduling.advisor-zone=Australia/Melbourne",
    "anna.scheduling.lead-time-min=60",
    "anna.scheduling.horizon-days=31",
    "anna.scheduling.hours-from=09:00",
    "anna.scheduling.hours-to=20:00",
    "anna.scheduling.sat-to=17:00",
    "anna.scheduling.event-min=15",
    "anna.scheduling.grid-min=30",
    "anna.scheduling.max-slots=6",
    "anna.scheduling.calendar-preset=fully_booked",
    "anna.scheduling.state-holidays[0]=2026-11-03",
    "anna.clock.mode=FIXED",
    "anna.clock.fixed-at=2026-07-26T00:00:00Z"
  };

  final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(Scheduling.class)
          .withPropertyValues(VALID);

  @Test
  void yamlValuesBindToTheirTypes() {
    runner.run(
        context -> {
          SchedulingProperties scheduling = context.getBean(SchedulingProperties.class);
          assertThat(scheduling.advisorZone()).isEqualTo(ZoneId.of("Australia/Melbourne"));
          assertThat(scheduling.hoursFrom()).isEqualTo(LocalTime.of(9, 0));
          assertThat(scheduling.calendarPreset()).isEqualTo(CalendarPreset.FULLY_BOOKED);
          assertThat(scheduling.stateHolidays()).containsExactly(LocalDate.of(2026, 11, 3));
          assertThat(context.getBean(ClockProperties.class).fixedAt())
              .isEqualTo(Instant.parse("2026-07-26T00:00:00Z"));
        });
  }

  @ParameterizedTest(name = "{0}={1}")
  @CsvSource({
    "anna.scheduling.horizon-days,    32, horizonDays",
    "anna.scheduling.lead-time-min,   -1, leadTimeMin",
    "anna.scheduling.hours-to,        '', hoursTo",
    "anna.scheduling.grid-min,        0,  gridMin",
    "anna.scheduling.max-slots,       0,  maxSlots",
    "anna.scheduling.calendar-preset, '', calendarPreset",
    "anna.scheduling.hours-to,        08:00, callFitsTheDay",
    "anna.scheduling.event-min,       600, callFitsTheDay",
    "anna.clock.mode,                 '', mode",
    "anna.clock.fixed-at,             '', fixedAtSetWhenFixed"
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
