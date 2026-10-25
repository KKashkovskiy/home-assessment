package com.enrola.anna.scheduling;

import com.enrola.anna.AppClock;
import com.enrola.anna.config.Settings;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * get_availability takes dates and a part of day on the lead's clock and returns at most the
 * settings' {@code maxSlots} slots, labelled for the lead. The window is cut at the horizon here
 * and held to the lead time and range by the {@link FakeCalendar}; the model never sees an offset.
 * A slot inside the advisors' hours is offered only if the call also falls within the weekday hours
 * on the lead's clock, so a Perth lead is not called at 6:00.
 */
@Component
public class AvailabilityTool {

  private static final ToolResult.Rejected BAD_DATE =
      new ToolResult.Rejected("BAD_DATE", "dates must be YYYY-MM-DD");
  private static final ToolResult.Rejected BAD_RANGE =
      new ToolResult.Rejected("BAD_RANGE", "date_to is before date_from");

  private final AppClock clock;
  private final FakeCalendar calendar;

  public AvailabilityTool(AppClock clock, FakeCalendar calendar) {
    this.clock = clock;
    this.calendar = calendar;
  }

  /** The model's arguments, as {@code tools.json} declares them; every one is required. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Args(String dateFrom, String dateTo, PartOfDay partOfDay) {

    public Args {
      Objects.requireNonNull(dateFrom, "date_from");
      Objects.requireNonNull(dateTo, "date_to");
      Objects.requireNonNull(partOfDay, "part_of_day");
    }
  }

  /** Parts of the lead's day as the prompt defines them, from inclusive to exclusive. */
  public enum PartOfDay {
    ANY(LocalTime.MIN, LocalTime.MAX),
    MORNING(LocalTime.of(9, 0), LocalTime.NOON),
    MIDDAY(LocalTime.of(11, 0), LocalTime.of(14, 0)),
    AFTERNOON(LocalTime.NOON, LocalTime.of(17, 0)),
    EVENING(LocalTime.of(17, 0), LocalTime.of(20, 0));

    private final LocalTime from;
    private final LocalTime to;

    PartOfDay(LocalTime from, LocalTime to) {
      this.from = from;
      this.to = to;
    }

    boolean contains(LocalTime time) {
      return !time.isBefore(from) && time.isBefore(to);
    }
  }

  /**
   * The first free slots from {@code date_from} through {@code date_to} in the lead's zone, in the
   * lead's part of day, under the turn's scheduling settings. A date that does not parse or that
   * {@code java.time} cannot place, or a range that runs backwards, is a failure; a window in the
   * past is an empty offer.
   */
  public ToolResult run(Args args, ToolContext context) {
    ZoneId leadZone = context.leadZone();
    Instant first;
    Instant last;
    try {
      LocalDate from = LocalDate.parse(args.dateFrom());
      LocalDate to = LocalDate.parse(args.dateTo());
      if (to.isBefore(from)) {
        return BAD_RANGE;
      }
      first = from.atStartOfDay(leadZone).toInstant();
      last = to.plusDays(1).atStartOfDay(leadZone).toInstant().minusNanos(1);
    } catch (DateTimeException _) {
      return BAD_DATE;
    }
    return offer(first, last, args.partOfDay(), context);
  }

  /**
   * Slots starting from {@code first} to {@code last}, cut at the horizon. The note says when the
   * window lies beyond the horizon, was cut at it, or holds nothing free; the filtering counts say
   * what each step left out.
   */
  private ToolResult.Slots offer(Instant first, Instant last, PartOfDay part, ToolContext context) {
    ZoneId leadZone = context.leadZone();
    Settings.Scheduling scheduling = context.scheduling();
    Instant now = clock.now();
    Instant horizon = now.plus(Duration.ofDays(scheduling.horizonDays()));
    String timezone = LeadZones.abbreviation(leadZone, now);
    if (first.isAfter(horizon)) {
      return new ToolResult.Slots(
          timezone, List.of(), ToolResult.Note.BEYOND_HORIZON, ToolResult.Filtering.NONE);
    }
    boolean clamped = last.isAfter(horizon);
    List<Instant> free = calendar.freeStarts(first, clamped ? horizon : last, scheduling);
    List<Instant> inPart =
        free.stream().filter(start -> part.contains(start.atZone(leadZone).toLocalTime())).toList();
    List<Instant> callable =
        inPart.stream()
            .filter(start -> withinLeadHours(start.atZone(leadZone), scheduling))
            .toList();
    List<Slot> slots =
        callable.stream()
            .limit(scheduling.maxSlots())
            .map(start -> Slot.of(start, leadZone))
            .toList();
    ToolResult.Filtering filtering =
        new ToolResult.Filtering(
            free.size(),
            free.size() - inPart.size(),
            inPart.size() - callable.size(),
            callable.size() - slots.size());
    return new ToolResult.Slots(timezone, slots, note(slots, clamped), filtering);
  }

  private static ToolResult.Note note(List<Slot> slots, boolean clamped) {
    if (slots.isEmpty()) {
      return ToolResult.Note.NO_SLOTS_IN_WINDOW;
    }
    return clamped ? ToolResult.Note.CLAMPED_TO_HORIZON : null;
  }

  /** The whole call within the weekday calling hours on the lead's clock. */
  private static boolean withinLeadHours(ZonedDateTime start, Settings.Scheduling scheduling) {
    LocalDateTime local = start.toLocalDateTime();
    return !local.toLocalTime().isBefore(scheduling.hoursFrom())
        && !local
            .plusMinutes(scheduling.eventMin())
            .isAfter(local.toLocalDate().atTime(scheduling.hoursTo()));
  }
}
