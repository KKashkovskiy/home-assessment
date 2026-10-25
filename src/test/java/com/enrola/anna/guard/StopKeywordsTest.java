package com.enrola.anna.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The opt-out table: what is a STOP and what is not. */
class StopKeywordsTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "STOP",
        " stop ",
        "Stop.",
        "STOP!",
        "stopall",
        "Unsubscribe",
        "OPT OUT",
        "opt-out",
        "optout",
        "cancel",
        "End",
        "QUIT",
        "S T O P",
        "stop\u00A0",
        "Stop\u2026",
        "\u201CSTOP\u201D",
        "Stop\u2019",
        "opt\u2013out"
      })
  void wholeMessageKeywordIsStop(String text) {
    assertThat(StopKeywords.isStop(text)).as(text).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "stop texting me please",
        "Cancel that, Thursday's better",
        "stop? no, go on",
        "I want to stop paying so much",
        "end of the month works",
        "quit smoking last year",
        "...",
        "\uFF33\uFF34\uFF2F\uFF30"
      })
  void proseIsNotStop(String text) {
    assertThat(StopKeywords.isStop(text)).as(text).isFalse();
  }

  /** CANCEL alone, read like any keyword; nothing else, prose with "cancel" in it included. */
  @ParameterizedTest(name = "\"{0}\" -> {1}")
  @CsvSource({
    "CANCEL, true",
    "' cancel. ', true",
    "Cancel!, true",
    "STOP, false",
    "cancel my call, false",
    "'Cancel that, Thursday''s better', false"
  })
  void loneCancelIsTheKeywordAlone(String text, boolean lone) {
    assertThat(StopKeywords.isLoneCancel(text)).isEqualTo(lone);
  }

  /** A repeat ignores case, punctuation and spaces; nothing repeats an empty message. */
  @ParameterizedTest(name = "\"{0}\" after \"{1}\" -> {2}")
  @CsvSource({
    "'Hello?', 'hello', true",
    "'are you there', 'Are you there?!', true",
    "'are you there', 'are you here', false",
    "'hi', '', false",
    "'...', '?', false"
  })
  void repeatIgnoresCasePunctuationAndSpaces(String text, String previous, boolean repeats) {
    assertThat(StopKeywords.repeats(text, previous)).isEqualTo(repeats);
  }
}
