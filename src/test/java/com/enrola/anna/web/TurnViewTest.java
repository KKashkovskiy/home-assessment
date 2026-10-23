package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.conversation.MessageSource;
import com.enrola.anna.conversation.TurnStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** What became of the model's reply, read from the turn's status and the message it sent. */
class TurnViewTest {

  @ParameterizedTest(name = "{0} turn that sent {1}: {2}")
  @CsvSource(
      nullValues = "none",
      value = {
        "OK,       LLM,   SENT",
        "REPAIRED, LLM,   REWRITTEN",
        "FALLBACK, FIXED, REPLACED",
        "OK,       FIXED, REPLACED",
        "NO_REPLY, none,  NOT_SENT",
        "ERROR,    none,  NOT_SENT"
      })
  void replyFateFollowsWhatWasSent(TurnStatus status, MessageSource sent, TurnView.ReplyFate fate) {
    assertThat(TurnView.ReplyFate.of(status, sent)).isEqualTo(fate);
  }
}
