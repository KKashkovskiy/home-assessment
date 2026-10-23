package com.enrola.anna.scheduling;

import com.enrola.anna.AppClock;
import com.enrola.anna.config.ClockProperties;
import com.enrola.anna.config.SchedulingProperties;
import com.enrola.anna.config.SchedulingProperties.CalendarPreset;
import com.enrola.anna.lead.Lead;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * The shipped scheduling defaults, a clock fixed on Sunday 26 July 2026, 10:00 in Melbourne, and
 * Lauren, a Victorian lead.
 */
final class SchedulingFixtures {

  static final ZoneId MELBOURNE = ZoneId.of("Australia/Melbourne");
  static final ZoneId PERTH = ZoneId.of("Australia/Perth");
  static final Instant SUNDAY = Instant.parse("2026-07-26T00:00:00Z");
  static final Lead LAUREN =
      new Lead("lauren", "Lauren", "+61400000001", null, "VIC", null, null, null, null, SUNDAY);

  private SchedulingFixtures() {}

  static SchedulingProperties defaults() {
    return props(CalendarPreset.NORMAL, List.of());
  }

  static SchedulingProperties props(CalendarPreset preset, List<LocalDate> stateHolidays) {
    return new SchedulingProperties(
        MELBOURNE,
        60,
        14,
        LocalTime.of(9, 0),
        LocalTime.of(20, 0),
        LocalTime.of(17, 0),
        15,
        30,
        6,
        preset,
        stateHolidays);
  }

  static AppClock clockAt(Instant at) {
    return new AppClock(new ClockProperties(ClockProperties.Mode.FIXED, at));
  }

  /** A failure's or rejection's code, or the kind of result otherwise; never the hint wording. */
  static String codeOf(ToolResult result) {
    return switch (result) {
      case ToolResult.Failure failure -> failure.code();
      case ToolResult.Rejected rejected -> rejected.code();
      case ToolResult.Slots _, ToolResult.Booked _, ToolResult.BookingUnknown _ ->
          result.getClass().getSimpleName();
    };
  }

  /** {@code day} at {@code time}, Melbourne wall time, as an instant. */
  static Instant melbourne(LocalDate day, LocalTime time) {
    return day.atTime(time).atZone(MELBOURNE).toInstant();
  }
}
