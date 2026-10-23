package com.enrola.anna.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The tables are the specification of the time normaliser. */
class TimeMentionsTest {

  static final List<String> OFFERED =
      List.of("Mon 27 Jul 12:00 PM AEST", "Mon 27 Jul 12:30 PM AEST", "Tue 28 Jul 9:00 AM AEST");

  static Stream<Arguments> parses() {
    return Stream.of(
        Arguments.of("9am", List.of(Set.of(at(9, 0)))),
        Arguments.of("9.30am", List.of(Set.of(at(9, 30)))),
        Arguments.of("9 or 9:30am", List.of(Set.of(at(9, 0), at(21, 0)), Set.of(at(9, 30)))),
        Arguments.of("noon", List.of(Set.of(at(12, 0)))),
        Arguments.of("at 12", List.of(Set.of(at(0, 0), at(12, 0)))),
        Arguments.of("14:00", List.of(Set.of(at(14, 0)))),
        Arguments.of("12:30pm", List.of(Set.of(at(12, 30)))),
        Arguments.of("around 10am", List.of(Set.of(at(10, 0)))),
        Arguments.of("around 9:30", List.of(Set.of(at(9, 30), at(21, 30)))),
        Arguments.of("1-3pm", List.of(Set.of(at(1, 0), at(13, 0)), Set.of(at(15, 0)))),
        Arguments.of("3pm-4pm", List.of(Set.of(at(15, 0)), Set.of(at(16, 0)))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  void parses(String reply, List<Set<LocalTime>> candidates) {
    assertThat(TimeMentions.mentions(reply))
        .extracting(TimeMentions.Mention::candidates)
        .containsExactlyElementsOf(candidates);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "about 15 mins",
        "27 Jul",
        "25 leading funds",
        "takes 15",
        "$350",
        "2 kids",
        "3 months",
        "0412 345 678",
        "at 15 mins",
        "at 3 months",
        "around midday",
        "a 15-min call"
      })
  void notTimes(String reply) {
    assertThat(TimeMentions.mentions(reply)).isEmpty();
  }

  static Stream<Arguments> checks() {
    return Stream.of(
        Arguments.of("Monday I've got 12:00 PM or 12:30, would one work?", OFFERED, false),
        Arguments.of("How about Monday at 3pm?", OFFERED, true),
        Arguments.of(
            "3pm isn't free, but 12:00 PM or 12:30 PM are, would one work?", OFFERED, false),
        Arguments.of("Tuesday at 9 or 9:30am?", List.of("Tue 28 Jul 9:00 AM AEST"), true),
        Arguments.of(
            "Tuesday at 9 or 9:30am?",
            List.of("Tue 28 Jul 9:00 AM AEST", "Tue 28 Jul 9:30 AM AEST"),
            false),
        Arguments.of(
            "11 or 1pm?", List.of("Mon 27 Jul 11:00 AM AEST", "Mon 27 Jul 1:00 PM AEST"), false),
        Arguments.of("Would 10am suit?", List.of(), true),
        Arguments.of("The call takes about 15 mins, would tomorrow suit?", List.of(), false),
        Arguments.of("Sorry, that time's gone. Would 3pm work?", List.of(), true),
        Arguments.of("Sorry, 10:00 is taken. Would 3pm work?", List.of(), true),
        Arguments.of(
            "Sorry, that 10:00 just went. I've still got Wednesday 11:00 AM or 2:00 PM."
                + " Would either of those work?",
            List.of("Wed 29 Jul 11:00 AM AEST", "Wed 29 Jul 2:00 PM AEST"),
            false),
        Arguments.of("Would 1-3pm work?", List.of("Mon 27 Jul 1:00 PM AEST"), true),
        Arguments.of("Would around 9:30 suit?", List.of(), true),
        Arguments.of("Only 10:30 left, ok?", List.of(), true),
        Arguments.of("Are you free Tuesday around midday for a quick call?", List.of(), false),
        Arguments.of(
            "Monday at 12:00 PM?",
            List.of("Mon 27 Jul 12:00" + Character.toString(0x202F) + "pm AEST"),
            false));
  }

  @ParameterizedTest(name = "{0} with {1}")
  @MethodSource
  void checks(String reply, List<String> offered, boolean violates) {
    assertThat(TimeMentions.check(reply, offered, null).map(Violation::check))
        .isEqualTo(violates ? Optional.of(ReplyCheck.TIME_MENTION) : Optional.empty());
  }

  @Test
  void bookingLabelIsAllowed() {
    assertThat(
            TimeMentions.check(
                "Booked - you're in for Monday 27 July at 12:00 PM AEST.",
                List.of(),
                "Mon 27 Jul 12:00 PM AEST"))
        .isEmpty();
  }

  static Stream<Arguments> distinctTimes() {
    return Stream.of(
        Arguments.of("12, 12:30 or 1pm", 3L),
        Arguments.of("12, 12:30, 1 or 1:30pm", 4L),
        Arguments.of("noon or 12pm", 1L),
        Arguments.of("3pm is taken, but 12:00 PM or 12:30 PM work", 2L));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  void distinctTimes(String reply, long expected) {
    assertThat(TimeMentions.distinctTimes(reply)).isEqualTo(expected);
  }

  private static LocalTime at(int hour, int minute) {
    return LocalTime.of(hour, minute);
  }
}
