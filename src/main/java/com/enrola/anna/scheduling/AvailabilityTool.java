package com.enrola.anna.scheduling;

import com.enrola.anna.AppClock;
import com.enrola.anna.config.SchedulingProperties;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * get_availability: dates and a part of day on the lead's clock in, at most {@code max-slots} slots
 * labelled for the lead out. The window is cut at the horizon here and held to the lead time and
 * range by the {@link FakeCalendar}; the model never sees an offset. A slot inside the advisors'
 * hours is offered only if the call also falls within the weekday hours on the lead's clock, so a
 * Perth lead is not called at 6:00.
 */
@Component
public class AvailabilityTool {

  private static final ToolResult.Rejected BAD_DATE =
      new ToolResult.Rejected("BAD_DATE", "dates must be YYYY-MM-DD");
  private static final ToolResult.Rejected BAD_RANGE =
      new ToolResult.Rejected("BAD_RANGE", "date_to is before date_from");

  /**
   * "Mon 27 Jul 12:00 PM"; the zone's name follows. English, not en-AU: on JDK 25 en-AU writes
   * "Sept", "July" and "pm", which the label format does not allow.
   */
  private static final DateTimeFormatter LABEL =
      DateTimeFormatter.ofPattern("EEE d MMM h:mm a", Locale.ENGLISH);

  private final AppClock clock;
  private final SchedulingProperties props;
  private final FakeCalendar calendar;

  public AvailabilityTool(AppClock clock, SchedulingProperties props, FakeCalendar calendar) {
    this.clock = clock;
    this.props = props;
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
   * The first free slots from {@code date_from} through {@code date_to} in {@code leadZone}, in the
   * lead's part of day. A date that does not parse or that {@code java.time} cannot place, or a
   * range that runs backwards, is a failure; a window in the past is an empty offer.
   */
  public ToolResult run(Args args, ZoneId leadZone) {
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
    return offer(first, last, args.partOfDay(), leadZone);
  }

  /**
   * Slots starting from {@code first} to {@code last}, cut at the horizon. The note says when the
   * window lies beyond the horizon, was cut at it, or holds nothing free.
   */
  private ToolResult.Slots offer(Instant first, Instant last, PartOfDay part, ZoneId leadZone) {
    Instant now = clock.now();
    Instant horizon = now.plus(Duration.ofDays(props.horizonDays()));
    String timezone = LeadZones.abbreviation(leadZone, now);
    if (first.isAfter(horizon)) {
      return new ToolResult.Slots(timezone, List.of(), ToolResult.Note.BEYOND_HORIZON);
    }
    boolean clamped = last.isAfter(horizon);
    List<Slot> slots =
        calendar.freeStarts(first, clamped ? horizon : last).stream()
            .filter(start -> part.contains(start.atZone(leadZone).toLocalTime()))
            .filter(start -> withinLeadHours(start.atZone(leadZone)))
            .limit(props.maxSlots())
            .map(start -> new Slot(Slot.idFor(start), start, label(start, leadZone)))
            .toList();
    if (slots.isEmpty()) {
      return new ToolResult.Slots(timezone, slots, ToolResult.Note.NO_SLOTS_IN_WINDOW);
    }
    return new ToolResult.Slots(
        timezone, slots, clamped ? ToolResult.Note.CLAMPED_TO_HORIZON : null);
  }

  /** The whole call within the weekday calling hours on the lead's clock. */
  private boolean withinLeadHours(ZonedDateTime start) {
    LocalDateTime local = start.toLocalDateTime();
    return !local.toLocalTime().isBefore(props.hoursFrom())
        && !local
            .plusMinutes(props.eventMin())
            .isAfter(local.toLocalDate().atTime(props.hoursTo()));
  }

  /**
   * The lead's wall time and the zone's name at that start, so a label after a DST change says so.
   */
  private static String label(Instant start, ZoneId leadZone) {
    return start.atZone(leadZone).format(LABEL) + " " + LeadZones.abbreviation(leadZone, start);
  }
}
