package com.enrola.anna.scheduling;

import com.enrola.anna.AppClock;
import com.enrola.anna.config.Settings;
import com.enrola.anna.config.Settings.CalendarPreset;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.lead.Lead;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * The shipped scheduling settings, a clock fixed on Sunday 26 July 2026, 10:00 in Melbourne, and
 * Lauren, a Victorian lead.
 */
final class SchedulingFixtures {

  static final ZoneId MELBOURNE = ZoneId.of("Australia/Melbourne");
  static final ZoneId PERTH = ZoneId.of("Australia/Perth");
  static final Instant SUNDAY = Instant.parse("2026-07-26T00:00:00Z");
  static final Lead LAUREN =
      new Lead("lauren", "Lauren", "+61400000001", null, "VIC", null, null, null, null, SUNDAY);

  /** Melbourne, 9:00 to 20:00 and Saturday to 17:00, 60 minutes' lead, 14 days, 6 slots. */
  static final Settings.Scheduling SCHEDULING = SettingsFixture.DEFAULTS.scheduling();

  private SchedulingFixtures() {}

  /** The shipped settings with the advisors' state closed on {@code stateHolidays}. */
  static Settings.Scheduling withHolidays(List<LocalDate> stateHolidays) {
    return new Settings.Scheduling(
        SCHEDULING.hoursFrom(),
        SCHEDULING.hoursTo(),
        SCHEDULING.satTo(),
        SCHEDULING.leadTimeMin(),
        SCHEDULING.horizonDays(),
        SCHEDULING.advisorZone(),
        SCHEDULING.eventMin(),
        SCHEDULING.gridMin(),
        SCHEDULING.maxSlots(),
        stateHolidays);
  }

  static AppClock clockAt(Instant at) {
    AppClock clock = new AppClock();
    clock.fixAt(at);
    return clock;
  }

  static FakeCalendar calendar(AppClock clock, CalendarPreset preset) {
    FakeCalendar calendar = new FakeCalendar(clock);
    calendar.preset(preset);
    return calendar;
  }

  /** A call answered for {@code lead} in {@code leadZone} under the shipped settings. */
  static ToolContext context(
      String conversationId, Lead lead, ZoneId leadZone, List<Slot> lastOffered) {
    return new ToolContext(conversationId, lead, leadZone, lastOffered, SCHEDULING);
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
