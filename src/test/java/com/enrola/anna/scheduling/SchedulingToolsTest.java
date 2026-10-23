package com.enrola.anna.scheduling;

import static com.enrola.anna.scheduling.SchedulingFixtures.MELBOURNE;
import static com.enrola.anna.scheduling.SchedulingFixtures.SUNDAY;
import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.AppClock;
import com.enrola.anna.config.SchedulingProperties.CalendarPreset;
import com.enrola.anna.lead.Lead;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What the model reads back from a function call, on the clock fixed on Sunday 26 July 2026. */
class SchedulingToolsTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final ToolContext CONTEXT =
      new ToolContext("c-1", SchedulingFixtures.LAUREN, MELBOURNE, List.of());

  static final Instant NOON_MONDAY = Instant.parse("2026-07-27T02:00:00Z");
  static final Slot NOON =
      new Slot(Slot.idFor(NOON_MONDAY), NOON_MONDAY, "Mon 27 Jul 12:00 PM AEST");
  static final String BOOK_NOON = "{\"slot_id\":\"" + NOON.slotId() + "\"}";
  static final String MONDAY_ANY =
      "{\"date_from\":\"2026-07-27\",\"date_to\":\"2026-07-27\",\"part_of_day\":\"ANY\"}";

  /**
   * No conversation has a booking and no row can be saved, as when the database refuses it;
   * anything else fails the test.
   */
  static final BookingRepository NO_BOOKINGS =
      (BookingRepository)
          Proxy.newProxyInstance(
              BookingRepository.class.getClassLoader(),
              new Class<?>[] {BookingRepository.class},
              (self, method, args) ->
                  switch (method.getName()) {
                    case "findByConversationId" -> Optional.empty();
                    case "save" -> throw new DataIntegrityViolationException("refused");
                    default -> throw new UnsupportedOperationException(method.getName());
                  });

  static final Class<ToolResult.Rejected> REJECTED = ToolResult.Rejected.class;
  static final Class<ToolResult.Failure> FAILURE = ToolResult.Failure.class;

  final SchedulingTools tools = toolsAt(SUNDAY);

  /** Ids and labels only: a start time would invite the model to do the arithmetic itself. */
  @Test
  void slotsReachTheModelAsIdsAndLocalLabels() {
    JsonNode answer =
        answer(
            "get_availability",
            "{\"date_from\":\"2026-07-27\",\"date_to\":\"2026-07-27\",\"part_of_day\":\"ANY\"}");

    assertThat(answer.get("timezone").stringValue()).isEqualTo("AEST");
    assertThat(answer.get("note").isNull()).isTrue();
    assertThat(answer.get("slots").values()).hasSize(6);
    JsonNode first = answer.get("slots").get(0);
    assertThat(first.propertyNames()).containsExactly("slot_id", "local_label");
    assertThat(first.get("slot_id").stringValue()).startsWith("s");
    assertThat(first.get("local_label").stringValue()).isEqualTo("Mon 27 Jul 9:00 AM AEST");
  }

  /**
   * Every code a call is answered with, and whose it is: a rejection is the model's mistake and a
   * failure the calendar's or the tool's, and only failures count toward a handoff.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("everyCode")
  void eachCodeIsARejectionOrAFailure(
      String code, ToolResult result, Class<? extends ToolResult> kind) {
    assertThat(result).isInstanceOf(kind);
    assertThat(JSON.readTree(SchedulingTools.modelJson(result)).get("code").stringValue())
        .isEqualTo(code);
  }

  static Stream<Arguments> everyCode() {
    ToolContext offeredNoon =
        new ToolContext("c-1", SchedulingFixtures.LAUREN, MELBOURNE, List.of(NOON));
    Lead noPhone = new Lead("pat", "Pat", " ", null, "VIC", null, null, null, null, SUNDAY);
    Instant mondayAfterNoon = NOON_MONDAY.plusSeconds(1800);
    return Stream.of(
        Arguments.of(
            "BAD_ARGS", run(toolsAt(SUNDAY), "get_availability", "{oops", offeredNoon), REJECTED),
        Arguments.of(
            "BAD_DATE",
            run(
                toolsAt(SUNDAY),
                "get_availability",
                "{\"date_from\":\"monday\",\"date_to\":\"2026-07-27\",\"part_of_day\":\"ANY\"}",
                offeredNoon),
            REJECTED),
        Arguments.of(
            "BAD_RANGE",
            run(
                toolsAt(SUNDAY),
                "get_availability",
                "{\"date_from\":\"2026-07-28\",\"date_to\":\"2026-07-27\",\"part_of_day\":\"ANY\"}",
                offeredNoon),
            REJECTED),
        Arguments.of(
            "UNKNOWN_TOOL", run(toolsAt(SUNDAY), "cancel_call", "{}", offeredNoon), REJECTED),
        Arguments.of(
            "UNKNOWN_SLOT",
            run(toolsAt(SUNDAY), "book_call", "{\"slot_id\":\"sNOPE\"}", offeredNoon),
            REJECTED),
        Arguments.of(
            "NO_PHONE",
            run(
                toolsAt(SUNDAY),
                "book_call",
                BOOK_NOON,
                new ToolContext("c-1", noPhone, MELBOURNE, List.of(NOON))),
            REJECTED),
        Arguments.of(
            "SLOT_TAKEN",
            run(
                tools(SchedulingFixtures.clockAt(SUNDAY), CalendarPreset.SLOT_TAKEN),
                "book_call",
                BOOK_NOON,
                offeredNoon),
            FAILURE),
        Arguments.of(
            "NOT_BOOKED",
            run(toolsAt(mondayAfterNoon), "book_call", BOOK_NOON, offeredNoon),
            FAILURE),
        Arguments.of(
            "TOOL_FAILED",
            run(brokenTools(), "get_availability", MONDAY_ANY, offeredNoon),
            FAILURE),
        Arguments.of(
            "UNKNOWN_BOOKING",
            run(toolsAt(SUNDAY), "book_call", BOOK_NOON, offeredNoon),
            ToolResult.BookingUnknown.class));
  }

  /** Arguments that are not the JSON the tool declares, in each way they can fail to be. */
  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          get_availability | {oops                                                                      | BAD_ARGS
          get_availability | null                                                                       | BAD_ARGS
          get_availability | {"date_from":"2026-07-27","date_to":"2026-07-27"}                         | BAD_ARGS
          get_availability | {"date_from":"2026-07-27","date_to":"2026-07-27","part_of_day":"NIGHT"} | BAD_ARGS
          book_call        | {}                                                                        | BAD_ARGS
          """)
  void badCallIsACompactError(String name, String arguments, String code) {
    JsonNode answer = answer(name, arguments);

    assertThat(answer.get("status").stringValue()).isEqualTo("ERROR");
    assertThat(answer.get("code").stringValue()).isEqualTo(code);
    assertThat(answer.get("hint").stringValue()).doesNotContain("Exception");
  }

  /** Each answer the model reads comes back as the console's one line. */
  @ParameterizedTest(name = "{1}")
  @MethodSource("answers")
  void summaryReadsBackEachAnswer(ToolResult result, String summary) {
    assertThat(SchedulingTools.summary(SchedulingTools.modelJson(result))).isEqualTo(summary);
  }

  static Stream<Arguments> answers() {
    Slot noon = new Slot("s1", Instant.parse("2026-07-27T02:00:00Z"), "Mon 27 Jul 12:00 PM AEST");
    return Stream.of(
        Arguments.of(new ToolResult.Slots("AEST", List.of(noon), null), "1 slot"),
        Arguments.of(
            new ToolResult.Slots("AEST", List.of(noon, noon), ToolResult.Note.CLAMPED_TO_HORIZON),
            "2 slots, CLAMPED_TO_HORIZON"),
        Arguments.of(
            new ToolResult.Slots("AEST", List.of(), ToolResult.Note.NO_SLOTS_IN_WINDOW),
            "0 slots, NO_SLOTS_IN_WINDOW"),
        Arguments.of(
            new ToolResult.Booked("cal-1", "Mon 27 Jul 12:00 PM AEST"),
            "booked Mon 27 Jul 12:00 PM AEST"),
        Arguments.of(new ToolResult.Failure("SLOT_TAKEN", "taken"), "SLOT_TAKEN"),
        Arguments.of(new ToolResult.Rejected("UNKNOWN_SLOT", "ask again"), "UNKNOWN_SLOT"),
        Arguments.of(new ToolResult.BookingUnknown("check"), "UNKNOWN_BOOKING"));
  }

  /** A tool that breaks still answers, so the turn goes on; the cause stays in the log. */
  @Test
  void brokenToolIsACompactError() {
    ToolResult result = brokenTools().execute("get_availability", MONDAY_ANY, CONTEXT);

    assertThat(result)
        .isInstanceOfSatisfying(
            ToolResult.Failure.class,
            failure -> {
              assertThat(failure.code()).isEqualTo("TOOL_FAILED");
              assertThat(failure.hint()).doesNotContain("calendar down");
            });
  }

  private JsonNode answer(String name, String arguments) {
    return JSON.readTree(SchedulingTools.modelJson(tools.execute(name, arguments, CONTEXT)));
  }

  private static ToolResult run(
      SchedulingTools tools, String name, String arguments, ToolContext context) {
    return tools.execute(name, arguments, context);
  }

  private static SchedulingTools toolsAt(Instant now) {
    return tools(SchedulingFixtures.clockAt(now), CalendarPreset.NORMAL);
  }

  private static SchedulingTools tools(AppClock clock, CalendarPreset preset) {
    return tools(clock, new FakeCalendar(clock, SchedulingFixtures.props(preset, List.of())));
  }

  private static SchedulingTools tools(AppClock clock, FakeCalendar calendar) {
    return new SchedulingTools(
        new AvailabilityTool(clock, SchedulingFixtures.defaults(), calendar),
        new BookCallTool(NO_BOOKINGS, calendar, clock));
  }

  /** The tools over a calendar that cannot answer. */
  private static SchedulingTools brokenTools() {
    AppClock clock = SchedulingFixtures.clockAt(SUNDAY);
    return tools(
        clock,
        new FakeCalendar(clock, SchedulingFixtures.defaults()) {
          @Override
          public List<Instant> freeStarts(Instant from, Instant to) {
            throw new IllegalStateException("calendar down");
          }
        });
  }
}
