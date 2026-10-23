package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.conversation.Conversation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A counter is at its limit when the next event ends the conversation or is refused. */
class ConsoleViewsTest {

  static final Settings.Limits LIMITS = SettingsFixture.DEFAULTS.limits();

  @ParameterizedTest(name = "turns {0}, objections {1}, off-topic {2}, closing {3}: {4}")
  @CsvSource({
    "0, 0, 0, 0, false false false false",
    "13, 0, 1, 0, false false false false",
    "14, 1, 2, 1, true true true true",
    "15, 0, 0, 0, true false false false"
  })
  void counterIsAtItsLimitOneEventBeforeTheEnd(
      int turns, int objections, int offTopic, int closing, String atLimit) {
    Conversation conversation = new Conversation();
    conversation.agentTurns = turns;
    conversation.objectionsUsed = objections;
    conversation.offTopicStreak = offTopic;
    conversation.postTerminalReplies = closing;

    assertThat(ConsoleViews.counters(conversation, LIMITS))
        .extracting(counter -> String.valueOf(counter.atLimit()))
        .containsExactly(atLimit.split(" "));
  }
}
