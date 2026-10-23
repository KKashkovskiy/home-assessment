package com.enrola.anna.scheduling;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * When the advisors call, on advisor-local dates: from {@code opens} to {@code weekdayCloses}
 * Monday to Friday, to {@code saturdayCloses} on Saturday, never on Sunday or a public holiday.
 */
public record AdvisorHours(
    LocalTime opens, LocalTime weekdayCloses, LocalTime saturdayCloses, PublicHolidays holidays) {

  /** The calling window on {@code day}, or empty when the advisors do not call. */
  public Optional<Window> on(LocalDate day) {
    if (holidays.isHoliday(day)) {
      return Optional.empty();
    }
    return switch (day.getDayOfWeek()) {
      case SUNDAY -> Optional.empty();
      case SATURDAY -> Optional.of(new Window(opens, saturdayCloses));
      case MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY ->
          Optional.of(new Window(opens, weekdayCloses));
    };
  }

  /** Open from {@code from}; a call must end by {@code to}. */
  public record Window(LocalTime from, LocalTime to) {}
}
