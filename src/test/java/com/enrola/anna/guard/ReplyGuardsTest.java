package com.enrola.anna.guard;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.lead.Lead;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Lauren, canary CANARY-7f3a, no slot labels, 320 chars, before the goal unless a test says. */
class ReplyGuardsTest {

  static final ReplyGuards GUARDS = new ReplyGuards();
  static final Lead LAUREN =
      new Lead(
          "lauren",
          "Lauren",
          "+61412345678",
          "lauren@example.com",
          "VIC",
          "Bupa",
          null,
          null,
          null,
          Instant.EPOCH);
  static final ReplyContext PRE_GOAL = context(false, false, List.of());
  static final ReplyContext POST_GOAL = context(true, false, List.of());
  static final ReplyContext TERMINAL = context(false, true, List.of());

  @Test
  void lengthBoundary() {
    assertThat(guards(ofLength(320), PRE_GOAL)).isEmpty();
    assertThat(guards(ofLength(321), PRE_GOAL)).containsExactly("length");
  }

  /** 322 chars as written, 320 as sent: the emoji goes, each curly quote stays one character. */
  @Test
  void lengthIsMeasuredOnTheTextAsSent() {
    String reply = "“" + "x".repeat(316) + "” 👍?";
    assertThat(reply).hasSize(322);

    ReplyGuards.Checked checked = GUARDS.validate(reply, PRE_GOAL);

    assertThat(checked.passed()).isTrue();
    assertThat(checked.text()).isEqualTo("\"" + "x".repeat(316) + "\" ?").hasSize(320);
  }

  static Stream<Arguments> questionCounts() {
    return Stream.of(
        Arguments.of("No question here.", PRE_GOAL, List.of("one-question")),
        Arguments.of("One? Two?", PRE_GOAL, List.of("one-question")),
        Arguments.of("Just one?", PRE_GOAL, List.of()),
        Arguments.of("Booked - the advisor will call then.", POST_GOAL, List.of()),
        Arguments.of("Anything else?", TERMINAL, List.of("closing-question")),
        Arguments.of("Take care.", TERMINAL, List.of()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  void questionCounts(String reply, ReplyContext context, List<String> violated) {
    assertThat(guards(reply, context)).isEqualTo(violated);
  }

  /** A full-width question mark is dropped by normalisation, so it is no question. */
  @Test
  void questionsAreCountedAfterNormalisation() {
    assertThat(guards("Which day suits？", PRE_GOAL)).containsExactly("one-question");
  }

  @Test
  void exclamation() {
    assertThat(guards("Great! Which day suits?", PRE_GOAL)).containsExactly("exclamation");
  }

  @Test
  void canary() {
    assertThat(guards("My notes say CANARY-7f3a, does that help?", PRE_GOAL))
        .containsExactly("canary");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "```code```?",
        "see `this` one?",
        "**bold** choice?",
        "# heading?",
        "Options:\n- bullet?",
        "see [link](http://x) maybe?"
      })
  void format(String reply) {
    assertThat(guards(reply, PRE_GOAL)).contains("format");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"Is 0412 345 678 still right?", "Is LAUREN@example.com still right?"})
  void contactDetailsAreNotEchoed(String reply) {
    assertThat(guards(reply, PRE_GOAL)).contains("pii");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "Lauren, which day suits?",
        "It takes 15 mins on 27 Jul 2026, which day suits?",
        "Is 0412 999 888 a typo?"
      })
  void otherNumbersAndTheNameAreFine(String reply) {
    assertThat(guards(reply, PRE_GOAL)).isEmpty();
  }

  @Test
  void timeWithNoSlotsFails() {
    assertThat(guards("Would Monday at 10am suit?", PRE_GOAL)).containsExactly("time-mention");
  }

  @Test
  void moreThanThreeSlots() {
    ReplyContext offered =
        context(
            false,
            false,
            List.of(
                "Mon 27 Jul 12:00 PM AEST",
                "Mon 27 Jul 12:30 PM AEST",
                "Mon 27 Jul 1:00 PM AEST",
                "Mon 27 Jul 1:30 PM AEST"));

    assertThat(guards("I've got 12, 12:30 or 1pm, would one work?", offered)).isEmpty();
    assertThat(guards("I've got 12, 12:30, 1 or 1:30pm, would one work?", offered))
        .containsExactly("max-slots");
  }

  @Test
  void denyListAndUrl() {
    assertThat(GUARDS.validate("You'll save hundreds, shall I book it?", PRE_GOAL).violations())
        .singleElement()
        .satisfies(violation -> assertThat(violation.check()).isEqualTo(ReplyCheck.DENY))
        .satisfies(violation -> assertThat(violation.note()).startsWith("savings-promise: "));
    assertThat(guards("Details at bit.ly/x, ok?", PRE_GOAL)).containsExactly("url");
  }

  @Test
  void blankReplyIsEmpty() {
    assertThat(guards(" 👍 ", PRE_GOAL)).containsExactly("empty", "one-question");
  }

  @Test
  void noteNamesEveryGuard() {
    List<Violation> violations =
        GUARDS.validate("Great! You'll save hundreds.", PRE_GOAL).violations();

    assertThat(ReplyGuards.note(violations))
        .contains("one-question: ")
        .contains("; exclamation: ")
        .contains("; deny: savings-promise: ");
  }

  private static ReplyContext context(boolean goalMet, boolean terminal, List<String> offered) {
    return new ReplyContext(LAUREN, "CANARY-7f3a", offered, null, goalMet, terminal, 320);
  }

  private static List<String> guards(String reply, ReplyContext context) {
    return GUARDS.validate(reply, context).violations().stream()
        .map(violation -> violation.check().traceName())
        .toList();
  }

  /** Filler ending in a question mark, exactly {@code length} chars. */
  private static String ofLength(int length) {
    return "x".repeat(length - 1) + "?";
  }
}
