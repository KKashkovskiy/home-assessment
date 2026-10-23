package com.enrola.anna.scheduling;

import static com.enrola.anna.scheduling.SchedulingFixtures.MELBOURNE;
import static com.enrola.anna.scheduling.SchedulingFixtures.PERTH;
import static com.enrola.anna.scheduling.SchedulingFixtures.SUNDAY;
import static com.enrola.anna.scheduling.SchedulingFixtures.melbourne;
import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.AppClock;
import com.enrola.anna.scheduling.AvailabilityTool.PartOfDay;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * get_availability on the clock fixed on Sunday 26 July 2026, with the real fake calendar: Lauren
 * in Melbourne, John in Perth.
 */
class AvailabilityToolTest {

  static final String MONDAY = "2026-07-27";

  final AppClock clock = SchedulingFixtures.clockAt(SUNDAY);
  final AvailabilityTool tool =
      new AvailabilityTool(
          clock,
          SchedulingFixtures.defaults(),
          new FakeCalendar(clock, SchedulingFixtures.defaults()));

  @Test
  void capsAtSix() {
    ToolResult.Slots result = slots(MONDAY, MONDAY, PartOfDay.ANY, MELBOURNE);

    assertThat(result.timezone()).isEqualTo("AEST");
    assertThat(result.note()).isNull();
    assertThat(result.slots()).hasSize(6);
    assertThat(result.slots().getFirst().label()).isEqualTo("Mon 27 Jul 9:00 AM AEST");
  }

  @Test
  void labelsCarryAbbreviation() {
    ToolResult.Slots result = slots(MONDAY, "2026-08-01", PartOfDay.EVENING, MELBOURNE);

    assertThat(result.slots())
        .isNotEmpty()
        .allMatch(
            slot ->
                slot.label()
                    .matches(
                        "^(Mon|Tue|Wed|Thu|Fri|Sat) \\d{1,2} \\w{3} \\d{1,2}:\\d{2} (AM|PM) [A-Z]{4}$"));
  }

  /** First grid start at or after 10:15. */
  @Test
  void dropsSlotsBeforeLeadTime() {
    clock.fixAt(melbourne(LocalDate.parse(MONDAY), LocalTime.of(9, 15)));

    assertThat(slots(MONDAY, MONDAY, PartOfDay.ANY, MELBOURNE).slots().getFirst().label())
        .isEqualTo("Mon 27 Jul 10:30 AM AEST");
  }

  /** Morning on John's clock is 11:00 onwards in Melbourne, two hours ahead. */
  @Test
  void morningFilterIsLeadLocal() {
    ToolResult.Slots result = slots(MONDAY, MONDAY, PartOfDay.MORNING, PERTH);

    assertThat(result.timezone()).isEqualTo("AWST");
    assertThat(result.slots().getFirst().label()).isEqualTo("Mon 27 Jul 9:00 AM AWST");
    assertThat(result.slots())
        .allMatch(slot -> slot.label().endsWith(" AM AWST"))
        .allMatch(
            slot -> !slot.startUtc().atZone(MELBOURNE).toLocalTime().isBefore(LocalTime.of(11, 0)));
  }

  /**
   * Melbourne moves to AEDT on Sunday 4 October and Brisbane, Darwin and Perth do not. Calls start
   * at 09:00 on both clocks, so a lead behind Melbourne starts later in Melbourne time: after the
   * change Melbourne's 09:00 is 08:00 in Brisbane, 07:30 in Darwin and 06:00 in Perth. Each label
   * carries the name in force at its own start, so Sydney's changes with the date.
   */
  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
    "Australia/Brisbane, 2026-10-02, Fri 2 Oct 9:00 AM AEST",
    "Australia/Brisbane, 2026-10-05, Mon 5 Oct 9:00 AM AEST",
    "Australia/Darwin,   2026-10-02, Fri 2 Oct 9:00 AM ACST",
    "Australia/Darwin,   2026-10-05, Mon 5 Oct 9:00 AM ACST",
    "Australia/Perth,    2026-10-02, Fri 2 Oct 9:00 AM AWST",
    "Australia/Perth,    2026-10-05, Mon 5 Oct 9:00 AM AWST",
    "Australia/Sydney,   2026-10-02, Fri 2 Oct 9:00 AM AEST",
    "Australia/Sydney,   2026-10-05, Mon 5 Oct 9:00 AM AEDT",
  })
  void firstSlotIsNineOnBothClocksAcrossTheDstChange(String zone, String day, String firstLabel) {
    clock.fixAt(melbourne(LocalDate.of(2026, 10, 1), LocalTime.NOON));

    assertThat(slots(day, day, PartOfDay.ANY, ZoneId.of(zone)).slots().getFirst().label())
        .isEqualTo(firstLabel);
  }

  @Test
  void saturdayEveningIsEmptyWithNote() {
    ToolResult.Slots result = slots("2026-08-01", "2026-08-01", PartOfDay.EVENING, MELBOURNE);

    assertThat(result.slots()).isEmpty();
    assertThat(result.note()).isEqualTo(ToolResult.Note.NO_SLOTS_IN_WINDOW);
  }

  /** The horizon ends 14 days on, on Sunday 9 August. */
  @Test
  void windowPastTheHorizonIsClampedOrNoted() {
    ToolResult.Slots clamped = slots("2026-08-06", "2026-08-20", PartOfDay.ANY, MELBOURNE);
    ToolResult.Slots beyond = slots("2026-08-15", "2026-08-15", PartOfDay.ANY, MELBOURNE);

    assertThat(clamped.slots()).isNotEmpty();
    assertThat(clamped.note()).isEqualTo(ToolResult.Note.CLAMPED_TO_HORIZON);
    assertThat(beyond.slots()).isEmpty();
    assertThat(beyond.note()).isEqualTo(ToolResult.Note.BEYOND_HORIZON);
  }

  @Test
  void pastDateFromIsClampedNotRejected() {
    ToolResult.Slots result = slots("2026-07-01", MONDAY, PartOfDay.ANY, MELBOURNE);

    assertThat(result.slots().getFirst().label()).isEqualTo("Mon 27 Jul 9:00 AM AEST");
  }

  @Test
  void windowAlreadyPastIsAnEmptyOffer() {
    ToolResult.Slots result = slots("2026-07-01", "2026-07-02", PartOfDay.ANY, MELBOURNE);

    assertThat(result.slots()).isEmpty();
    assertThat(result.note()).isEqualTo(ToolResult.Note.NO_SLOTS_IN_WINDOW);
  }

  /**
   * The last date java.time can parse cannot be placed in a zone: a rejected call, not a crash. The
   * dates are the model's mistake, so neither counts against the calendar.
   */
  @Test
  void badDateOrRangeIsRejected() {
    assertThat(run("next monday", MONDAY)).isInstanceOf(ToolResult.Rejected.class);
    assertThat(SchedulingFixtures.codeOf(run("next monday", MONDAY))).isEqualTo("BAD_DATE");
    assertThat(SchedulingFixtures.codeOf(run(MONDAY, "+999999999-12-31"))).isEqualTo("BAD_DATE");
    assertThat(run("2026-07-28", MONDAY)).isInstanceOf(ToolResult.Rejected.class);
    assertThat(SchedulingFixtures.codeOf(run("2026-07-28", MONDAY))).isEqualTo("BAD_RANGE");
  }

  /**
   * The parts of day as the prompt's scheduling rules state them (morning 9-12, midday 11-14,
   * afternoon 12-17, evening 17-20), each from inclusive to exclusive. This table is what keeps the
   * code and {@code prompts/v1/v1.md} in step: change one, and this test says to change the other.
   */
  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
    "ANY,       00:00, true",
    "ANY,       23:59, true",
    "MORNING,   08:59, false",
    "MORNING,   09:00, true",
    "MORNING,   11:59, true",
    "MORNING,   12:00, false",
    "MIDDAY,    10:59, false",
    "MIDDAY,    11:00, true",
    "MIDDAY,    13:59, true",
    "MIDDAY,    14:00, false",
    "AFTERNOON, 11:59, false",
    "AFTERNOON, 12:00, true",
    "AFTERNOON, 16:59, true",
    "AFTERNOON, 17:00, false",
    "EVENING,   16:59, false",
    "EVENING,   17:00, true",
    "EVENING,   19:59, true",
    "EVENING,   20:00, false",
  })
  void partsOfDayMatchThePrompt(PartOfDay part, LocalTime time, boolean contains) {
    assertThat(part.contains(time)).isEqualTo(contains);
  }

  private ToolResult run(String from, String to) {
    return tool.run(new AvailabilityTool.Args(from, to, PartOfDay.ANY), MELBOURNE);
  }

  private ToolResult.Slots slots(String from, String to, PartOfDay part, ZoneId lead) {
    return (ToolResult.Slots) tool.run(new AvailabilityTool.Args(from, to, part), lead);
  }
}
