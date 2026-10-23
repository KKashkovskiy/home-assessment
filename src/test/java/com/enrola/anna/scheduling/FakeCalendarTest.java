package com.enrola.anna.scheduling;

import static com.enrola.anna.scheduling.SchedulingFixtures.LAUREN;
import static com.enrola.anna.scheduling.SchedulingFixtures.MELBOURNE;
import static com.enrola.anna.scheduling.SchedulingFixtures.SUNDAY;
import static com.enrola.anna.scheduling.SchedulingFixtures.codeOf;
import static com.enrola.anna.scheduling.SchedulingFixtures.melbourne;
import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.AppClock;
import com.enrola.anna.config.SchedulingProperties.CalendarPreset;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Slot generation with the shipped defaults, on the clock fixed on Sunday 26 July 2026. */
class FakeCalendarTest {

  static final LocalDate MONDAY = LocalDate.of(2026, 7, 27);
  static final LocalDate SATURDAY = LocalDate.of(2026, 8, 1);
  static final Instant MONDAY_NOON = melbourne(MONDAY, LocalTime.NOON);
  static final Slot NOON =
      new Slot(Slot.idFor(MONDAY_NOON), MONDAY_NOON, "Mon 27 Jul 12:00 PM AEST");

  final AppClock clock = SchedulingFixtures.clockAt(SUNDAY);
  final FakeCalendar calendar = new FakeCalendar(clock, SchedulingFixtures.defaults());

  @Test
  void firstSlotIsMondayNine() {
    assertThat(week(calendar).getFirst()).isEqualTo(MONDAY.atTime(9, 0));
  }

  @Test
  void noSundaySlots() {
    assertThat(week(calendar))
        .isNotEmpty()
        .noneMatch(start -> start.getDayOfWeek() == DayOfWeek.SUNDAY);
  }

  /** A 15-minute call must end by closing time: 20:00 on weekdays, 17:00 on Saturday. */
  @Test
  void lastSlotEndsByClosingTime() {
    List<LocalDateTime> week = week(calendar);

    assertThat(lastStartOn(week, MONDAY)).isEqualTo(LocalTime.of(19, 30));
    assertThat(lastStartOn(week, SATURDAY)).isEqualTo(LocalTime.of(16, 30));
  }

  /** Christmas Day and Boxing Day fall on Friday and Saturday; Monday 28 is the substitute day. */
  @Test
  void christmasExcluded() {
    clock.fixAt(melbourne(LocalDate.of(2026, 12, 20), LocalTime.NOON));

    List<LocalDate> days =
        calendar
            .freeStarts(
                melbourne(LocalDate.of(2026, 12, 24), LocalTime.MIN),
                melbourne(LocalDate.of(2026, 12, 28), LocalTime.MAX))
            .stream()
            .map(start -> start.atZone(MELBOURNE).toLocalDate())
            .distinct()
            .toList();

    assertThat(days).containsExactly(LocalDate.of(2026, 12, 24));
  }

  @Test
  void stateHolidayFromConfigExcluded() {
    LocalDate tuesday = MONDAY.plusDays(1);
    FakeCalendar withHoliday =
        new FakeCalendar(clock, SchedulingFixtures.props(CalendarPreset.NORMAL, List.of(tuesday)));

    assertThat(dayOf(withHoliday, tuesday)).isEmpty();
    assertThat(dayOf(calendar, tuesday)).isNotEmpty();
  }

  @Test
  void fullyBookedReturnsNone() {
    FakeCalendar fullyBooked =
        new FakeCalendar(clock, SchedulingFixtures.props(CalendarPreset.FULLY_BOOKED, List.of()));

    assertThat(week(fullyBooked)).isEmpty();
  }

  /** Never before now plus the lead time, never past the calendar's own 31-day range. */
  @Test
  void rangeClampedToLeadTimeAndThirtyOneDays() {
    List<Instant> starts = calendar.freeStarts(SUNDAY.minus(Duration.ofDays(1)), plusDays(60));

    assertThat(starts.getFirst()).isAfterOrEqualTo(SUNDAY.plus(Duration.ofMinutes(60)));
    assertThat(starts.getLast()).isBetween(plusDays(30), plusDays(31));
  }

  /** A window that ends before now plus the lead time holds nothing, whatever its dates. */
  @Test
  void windowBeforeTheLeadTimeIsEmpty() {
    assertThat(calendar.freeStarts(SUNDAY.minus(Duration.ofDays(3)), SUNDAY)).isEmpty();
  }

  /** Explicit slots replace generation; one already past is dropped like a generated one. */
  @Test
  void fixtureOverridesGeneration() {
    Instant monday = melbourne(MONDAY, LocalTime.of(10, 0));
    Instant wednesday = melbourne(MONDAY.plusDays(2), LocalTime.of(15, 0));
    Instant past = melbourne(MONDAY.minusDays(2), LocalTime.of(10, 0));
    calendar.withSlots(
        List.of(
            new Slot("a", monday, "a"), new Slot("b", wednesday, "b"), new Slot("c", past, "c")));

    assertThat(calendar.freeStarts(past, plusDays(14))).containsExactly(monday, wednesday);
  }

  @Test
  void freeStartIsBookedWithAFreshEventId() {
    ToolResult first = calendar.book(NOON, LAUREN);
    ToolResult second = calendar.book(NOON, LAUREN);

    assertThat(first)
        .isInstanceOfSatisfying(
            ToolResult.Booked.class,
            event -> {
              assertThat(event.bookingId()).startsWith("cal-");
              assertThat(event.label()).isEqualTo(NOON.label());
            });
    assertThat(second).isNotEqualTo(first);
  }

  @Test
  void slotTakenPresetRefusesTheFirstBookingOnly() {
    FakeCalendar slotTaken =
        new FakeCalendar(clock, SchedulingFixtures.props(CalendarPreset.SLOT_TAKEN, List.of()));

    assertThat(codeOf(slotTaken.book(NOON, LAUREN))).isEqualTo("SLOT_TAKEN");
    assertThat(codeOf(slotTaken.book(NOON, LAUREN))).isEqualTo("Booked");
  }

  /** An offer goes stale: by Monday 11:30 the noon start is inside the lead time. */
  @Test
  void startNoLongerFreeIsNotBooked() {
    clock.fixAt(melbourne(MONDAY, LocalTime.of(11, 30)));

    assertThat(codeOf(calendar.book(NOON, LAUREN))).isEqualTo("NOT_BOOKED");
  }

  /** Every free start from Sunday 26 July to Sunday 2 August, as Melbourne wall time. */
  private static List<LocalDateTime> week(FakeCalendar calendar) {
    return calendar.freeStarts(melbourne(MONDAY.minusDays(1), LocalTime.MIN), plusDays(7)).stream()
        .map(start -> start.atZone(MELBOURNE).toLocalDateTime())
        .toList();
  }

  private static List<Instant> dayOf(FakeCalendar calendar, LocalDate day) {
    return calendar.freeStarts(melbourne(day, LocalTime.MIN), melbourne(day, LocalTime.MAX));
  }

  private static LocalTime lastStartOn(List<LocalDateTime> starts, LocalDate day) {
    return starts.stream()
        .filter(start -> start.toLocalDate().equals(day))
        .map(LocalDateTime::toLocalTime)
        .max(LocalTime::compareTo)
        .orElseThrow();
  }

  private static Instant plusDays(int days) {
    return SUNDAY.plus(Duration.ofDays(days));
  }
}
