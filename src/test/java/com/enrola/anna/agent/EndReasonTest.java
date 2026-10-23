package com.enrola.anna.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EndReasonTest {

  @ParameterizedTest(name = "{0} hard-terminal: {1}")
  @CsvSource({
    "NONE,            false",
    "BOOKED,          false",
    "DECLINED,        false",
    "UNSUBSCRIBED,    true",
    "ABUSIVE,         true",
    "OFF_TOPIC_LIMIT, true",
    "MAX_TURNS,       false",
    "HANDOFF,         false",
    "SAFETY,          true"
  })
  void hardTerminalReasonsSilenceLaterMessages(EndReason reason, boolean hardTerminal) {
    assertThat(reason.isHardTerminal()).isEqualTo(hardTerminal);
  }
}
