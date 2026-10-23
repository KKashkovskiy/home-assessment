package com.enrola.anna.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;

/**
 * How call slots are generated and offered. Calling hours are advisor-local, in {@code
 * advisorZone}, which is also the zone of a lead whose state is unknown, and the weekday hours hold
 * on the lead's clock too. The horizon stays within the calendar's 31-day range. {@code
 * stateHolidays} are the public holidays of the advisors' own state, on top of the national ones.
 */
@Validated
@ConfigurationProperties(prefix = "anna.scheduling")
public record SchedulingProperties(
    @NotNull ZoneId advisorZone,
    @PositiveOrZero int leadTimeMin,
    @Positive @Max(31) int horizonDays,
    @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime hoursFrom,
    @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime hoursTo,
    @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime satTo,
    @Positive int eventMin,
    @Positive int gridMin,
    @Positive int maxSlots,
    @NotNull CalendarPreset calendarPreset,
    @NotNull List<LocalDate> stateHolidays) {

  /**
   * How the calendar behaves, for testing the paths a real one would take: as usual, with nothing
   * free, or with the next booking answered as taken. Availability is the same for NORMAL and
   * SLOT_TAKEN; only booking tells them apart.
   */
  public enum CalendarPreset {
    NORMAL,
    FULLY_BOOKED,
    SLOT_TAKEN
  }

  /** At least one call fits on a Saturday, and Saturday closes no later than a weekday. */
  @AssertTrue(message = "hours-from plus event-min must fit by sat-to, and sat-to by hours-to") public boolean isCallFitsTheDay() {
    if (hoursFrom == null || hoursTo == null || satTo == null) {
      return true; // reported by @NotNull
    }
    return !hoursFrom.plusMinutes(eventMin).isAfter(satTo)
        && hoursFrom.plusMinutes(eventMin).isAfter(hoursFrom)
        && !satTo.isAfter(hoursTo);
  }
}
