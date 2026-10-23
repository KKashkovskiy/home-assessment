package com.enrola.anna.scheduling;

import static com.enrola.anna.scheduling.SchedulingFixtures.MELBOURNE;
import static com.enrola.anna.scheduling.SchedulingFixtures.SUNDAY;
import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.AppClock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What the model reads back from a function call, on the clock fixed on Sunday 26 July 2026. */
class SchedulingToolsTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  final AppClock clock = SchedulingFixtures.clockAt(SUNDAY);
  final SchedulingTools tools =
      new SchedulingTools(
          new AvailabilityTool(
              clock,
              SchedulingFixtures.defaults(),
              new FakeCalendar(clock, SchedulingFixtures.defaults())));

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

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          get_availability | {oops                                                                      | BAD_ARGS
          get_availability | null                                                                       | BAD_ARGS
          get_availability | {"date_from":"2026-07-27","date_to":"2026-07-27"}                         | BAD_ARGS
          get_availability | {"date_from":"2026-07-27","date_to":"2026-07-27","part_of_day":"NIGHT"} | BAD_ARGS
          book_call        | {"slot_id":"sMTIz"}                                                       | NOT_AVAILABLE_YET
          cancel_call      | {}                                                                        | UNKNOWN_TOOL
          """)
  void badOrUnavailableCallIsACompactError(String name, String arguments, String code) {
    JsonNode answer = answer(name, arguments);

    assertThat(answer.get("status").stringValue()).isEqualTo("ERROR");
    assertThat(answer.get("code").stringValue()).isEqualTo(code);
    assertThat(answer.get("hint").stringValue()).doesNotContain("Exception");
  }

  /** A tool that breaks still answers, so the turn goes on and the error is counted. */
  @Test
  void brokenToolIsACompactError() {
    FakeCalendar broken =
        new FakeCalendar(clock, SchedulingFixtures.defaults()) {
          @Override
          public List<Instant> freeStarts(Instant from, Instant to) {
            throw new IllegalStateException("calendar down");
          }
        };
    SchedulingTools withBrokenCalendar =
        new SchedulingTools(new AvailabilityTool(clock, SchedulingFixtures.defaults(), broken));

    ToolResult result =
        withBrokenCalendar.execute(
            "get_availability",
            "{\"date_from\":\"2026-07-27\",\"date_to\":\"2026-07-27\",\"part_of_day\":\"ANY\"}",
            MELBOURNE);

    assertThat(result)
        .isInstanceOfSatisfying(
            ToolResult.Failure.class,
            failure -> {
              assertThat(failure.code()).isEqualTo("TOOL_FAILED");
              assertThat(failure.hint()).doesNotContain("calendar down");
            });
  }

  private JsonNode answer(String name, String arguments) {
    return JSON.readTree(SchedulingTools.modelJson(tools.execute(name, arguments, MELBOURNE)));
  }
}
