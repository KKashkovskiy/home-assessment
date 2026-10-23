package com.enrola.anna.scheduling;

import com.enrola.anna.AppClock;
import com.enrola.anna.config.SchedulingProperties;
import com.enrola.anna.config.SchedulingProperties.CalendarPreset;
import com.enrola.anna.lead.Lead;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * The calendar: a deterministic stand-in for Calendly, and the only one there is. Free starts come
 * from the clock, the advisor hours, the slot grid and the preset; nothing is stored, so a booking
 * takes no start from anyone else. It keeps the real API's limits, a 31-day range and no start in
 * the past, so the tool contract would not change under a real calendar.
 */
@Component
public class FakeCalendar {

  /** Calendly's own range cap. */
  private static final Duration MAX_RANGE = Duration.ofDays(31);

  private static final ToolResult.Failure SLOT_TAKEN =
      new ToolResult.Failure("SLOT_TAKEN", "slot was just taken; call get_availability again");
  private static final ToolResult.Failure NOT_BOOKED =
      new ToolResult.Failure("NOT_BOOKED", "slot is no longer available; re-check availability");

  private final AppClock clock;
  private final SchedulingProperties props;
  private final AdvisorHours hours;

  /** Set once the SLOT_TAKEN preset has answered a booking as taken. */
  private final AtomicBoolean takenOnce = new AtomicBoolean();

  /** Explicit slots for a scenario; null means generate them from the hours. */
  private volatile List<Slot> fixture;

  public FakeCalendar(AppClock clock, SchedulingProperties props) {
    this.clock = clock;
    this.props = props;
    this.hours =
        new AdvisorHours(
            props.hoursFrom(),
            props.hoursTo(),
            props.satTo(),
            new PublicHolidays(props.stateHolidays()));
  }

  /**
   * Free starts from {@code from} to {@code to} inclusive, in order. None is earlier than now plus
   * the lead time or beyond the 31-day range. The caller labels them for the lead.
   */
  public List<Instant> freeStarts(Instant from, Instant to) {
    if (props.calendarPreset() == CalendarPreset.FULLY_BOOKED) {
      return List.of();
    }
    Instant now = clock.now();
    Instant earliest = later(from, now.plus(Duration.ofMinutes(props.leadTimeMin())));
    Instant latest = earlier(to, now.plus(MAX_RANGE));
    if (earliest.isAfter(latest)) {
      return List.of();
    }
    List<Slot> explicit = fixture;
    Stream<Instant> starts =
        explicit == null ? gridStarts(earliest, latest) : explicit.stream().map(Slot::startUtc);
    return starts.filter(start -> !start.isBefore(earliest) && !start.isAfter(latest)).toList();
  }

  /**
   * Books {@code slot} for {@code invitee}: SLOT_TAKEN for the first booking under the SLOT_TAKEN
   * preset, NOT_BOOKED for a start that is no longer free, else BOOKED with a fresh event id. The
   * invitee is required as the real call requires it; the fake keeps nothing of it.
   */
  public ToolResult book(Slot slot, Lead invitee) {
    Objects.requireNonNull(invitee, "invitee");
    if (props.calendarPreset() == CalendarPreset.SLOT_TAKEN
        && takenOnce.compareAndSet(false, true)) {
      return SLOT_TAKEN;
    }
    Instant start = slot.startUtc();
    return freeStarts(start, start).contains(start)
        ? new ToolResult.Booked("cal-" + UUID.randomUUID(), slot.label())
        : NOT_BOOKED;
  }

  /** Scenario hook: exactly these slots instead of the grid, still held to the same limits. */
  public void withSlots(List<Slot> explicit) {
    fixture = List.copyOf(explicit);
  }

  /** Every grid start on the advisor-local days the window touches. */
  private Stream<Instant> gridStarts(Instant earliest, Instant latest) {
    ZoneId zone = props.advisorZone();
    return earliest
        .atZone(zone)
        .toLocalDate()
        .datesUntil(latest.atZone(zone).toLocalDate().plusDays(1))
        .flatMap(day -> hours.on(day).stream().flatMap(window -> startsIn(day, window)));
  }

  /**
   * Starts on the grid from opening until a call would end after closing. The grid is wall time, so
   * {@code atZone} places each start on the right side of a daylight-saving change.
   */
  private Stream<Instant> startsIn(LocalDate day, AdvisorHours.Window window) {
    LocalDateTime lastStart = day.atTime(window.to()).minusMinutes(props.eventMin());
    return Stream.iterate(
            day.atTime(window.from()),
            start -> !start.isAfter(lastStart),
            start -> start.plusMinutes(props.gridMin()))
        .map(start -> start.atZone(props.advisorZone()).toInstant());
  }

  private static Instant later(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  private static Instant earlier(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
