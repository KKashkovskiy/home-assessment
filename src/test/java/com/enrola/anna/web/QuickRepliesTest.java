package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** One line of the quick-replies file: a label and the text it sends. */
class QuickRepliesTest {

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = ';',
      value = {
        "STOP | STOP; STOP; STOP",
        "not now | not now, maybe next month; not now; not now, maybe next month",
        "are you a bot?; are you a bot?; are you a bot?",
        "a |b | c; a; b | c"
      })
  void lineIsALabelAndTheTextAfterTheFirstBar(String line, String label, String text) {
    assertThat(QuickReplies.parse(line)).isEqualTo(new QuickReplies.QuickReply(label, text));
  }
}
