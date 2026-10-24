package com.enrola.anna.scheduling;

import com.enrola.anna.AppClock;
import com.enrola.anna.config.Settings;
import com.enrola.anna.config.Settings.CalendarPreset;
import com.enrola.anna.config.SettingsListener;
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
 * from the clock, the turn's scheduling settings and the preset; nothing is stored, so a booking
 * takes no start from anyone else. It keeps the real API's limits, a 31-day range and no start in
 * the past, so the tool contract would not change under a real calendar. The preset follows the
 * settings from boot and after every save that changes it; a scenario can set it directly.
 */
@Component
public class FakeCalendar implements SettingsListener {

  /** Calendly's own range cap. */
  private static final Duration MAX_RANGE = Duration.ofDays(31);

  private static final ToolResult.Failure SLOT_TAKEN =
      new ToolResult.Failure("SLOT_TAKEN", "slot was just taken; call get_availability again");
  private static final ToolResult.Failure NOT_BOOKED =
      new ToolResult.Failure("NOT_BOOKED", "slot is no longer available; re-check availability");

  private final AppClock clock;

  /** Set once the SLOT_TAKEN preset has answered a booking as taken. */
  private final AtomicBoolean takenOnce = new AtomicBoolean();

  private volatile CalendarPreset preset = CalendarPreset.NORMAL;

  /** Explicit slots for a scenario; null means generate them from the hours. */
  private volatile List<Slot> fixture;

  /** A calendar on the NORMAL preset until the settings or a scenario switch it. */
  public FakeCalendar(AppClock clock) {
    this.clock = clock;
  }

  /** A save that leaves the preset as it was keeps SLOT_TAKEN's one refusal spent. */
  @Override
  public void settingsChanged(Settings current) {
    if (current.calendarPreset() != preset) {
      preset(current.calendarPreset());
    }
  }

  /** Switches the preset; SLOT_TAKEN answers the next booking as taken. */
  public void preset(CalendarPreset next) {
    takenOnce.set(false);
    preset = next;
  }

  /**
   * Free starts from {@code from} to {@code to} inclusive, in order, in the advisor hours of {@code
   * scheduling}. None is earlier than now plus the lead time or beyond the 31-day range. The caller
   * labels them for the lead.
   */
  public List<Instant> freeStarts(Instant from, Instant to, Settings.Scheduling scheduling) {
    if (preset == CalendarPreset.FULLY_BOOKED) {
      return List.of();
    }
    Instant now = clock.now();
    Instant earliest = later(from, now.plus(Duration.ofMinutes(scheduling.leadTimeMin())));
    Instant latest = earlier(to, now.plus(MAX_RANGE));
    if (earliest.isAfter(latest)) {
      return List.of();
    }
    List<Slot> explicit = fixture;
    Stream<Instant> starts =
        explicit == null
            ? gridStarts(earliest, latest, scheduling)
            : explicit.stream().map(Slot::startUtc);
    return starts.filter(start -> !start.isBefore(earliest) && !start.isAfter(latest)).toList();
  }

  /**
   * Books {@code slot} for {@code invitee}: SLOT_TAKEN for the first booking under the SLOT_TAKEN
   * preset, NOT_BOOKED for a start no longer free in {@code scheduling}'s hours, else BOOKED with a
   * fresh event id. The invitee is required as the real call requires it; the fake keeps nothing of
   * it.
   */
  public ToolResult book(Slot slot, Lead invitee, Settings.Scheduling scheduling) {
    Objects.requireNonNull(invitee, "invitee");
    if (preset == CalendarPreset.SLOT_TAKEN && takenOnce.compareAndSet(false, true)) {
      return SLOT_TAKEN;
    }
    Instant start = slot.startUtc();
    return freeStarts(start, start, scheduling).contains(start)
        ? new ToolResult.Booked("cal-" + UUID.randomUUID(), slot.label())
        : NOT_BOOKED;
  }

  /**
   * Scenario seeding: the slots labelled {@code labels} for a lead in {@code leadZone}, in that
   * order, from the grid of the 31 days from now. The preset and the lead time do not apply, since
   * a seeded offer is history; a label the grid cannot produce, a Sunday or a time off the grid,
   * throws {@link IllegalArgumentException}.
   */
  public List<Slot> slotsForLabels(
      List<String> labels, ZoneId leadZone, Settings.Scheduling scheduling) {
    Instant now = clock.now();
    List<Slot> grid =
        gridStarts(now, now.plus(MAX_RANGE), scheduling)
            .map(start -> Slot.of(start, leadZone))
            .toList();
    return labels.stream().map(label -> labelled(grid, label, now)).toList();
  }

  private static Slot labelled(List<Slot> grid, String label, Instant now) {
    return grid.stream()
        .filter(slot -> slot.label().equals(label))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "no slot labelled '" + label + "' in the 31 days from " + now));
  }

  /** Scenario hook: exactly these slots instead of the grid, still held to the same limits. */
  public void withSlots(List<Slot> explicit) {
    fixture = List.copyOf(explicit);
  }

  /** Every grid start on the advisor-local days the window touches. */
  private static Stream<Instant> gridStarts(
      Instant earliest, Instant latest, Settings.Scheduling scheduling) {
    ZoneId zone = scheduling.advisorZone();
    AdvisorHours hours =
        new AdvisorHours(
            scheduling.hoursFrom(),
            scheduling.hoursTo(),
            scheduling.satTo(),
            new PublicHolidays(scheduling.stateHolidays()));
    return earliest
        .atZone(zone)
        .toLocalDate()
        .datesUntil(latest.atZone(zone).toLocalDate().plusDays(1))
        .flatMap(
            day -> hours.on(day).stream().flatMap(window -> startsIn(day, window, scheduling)));
  }

  /**
   * Starts on the grid from opening until a call would end after closing. The grid is wall time, so
   * {@code atZone} places each start on the right side of a daylight-saving change.
   */
  private static Stream<Instant> startsIn(
      LocalDate day, AdvisorHours.Window window, Settings.Scheduling scheduling) {
    LocalDateTime lastStart = day.atTime(window.to()).minusMinutes(scheduling.eventMin());
    return Stream.iterate(
            day.atTime(window.from()),
            start -> !start.isAfter(lastStart),
            start -> start.plusMinutes(scheduling.gridMin()))
        .map(start -> start.atZone(scheduling.advisorZone()).toInstant());
  }

  private static Instant later(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  private static Instant earlier(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
