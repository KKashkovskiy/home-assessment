package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enrola.anna.conversation.Conversation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The stop_when grammar and when each condition holds. */
class StopWhenTest {

  @Test
  void parsesCap() {
    assertThat(StopWhen.parse("6 lead messages have been sent")).isEqualTo(new StopWhen.Cap(6));
    assertThat(StopWhen.parse("4 turns")).isEqualTo(new StopWhen.Cap(4));
  }

  @Test
  void parsesStateAndGoodbye() {
    assertThat(StopWhen.parse("goal_met")).isEqualTo(StopWhen.State.GOAL_MET);
    assertThat(StopWhen.parse("The agent says it will leave it there or says goodbye"))
        .isEqualTo(new StopWhen.Goodbye());
  }

  @Test
  void unknownThrows() {
    assertThatThrownBy(() -> StopWhen.parse("when bored"))
        .isInstanceOf(ScenarioException.class)
        .hasMessage("unknown stop_when: when bored");
  }

  @Test
  void aCapIsAppendedOnlyWhenNoneIsGiven() {
    assertThat(StopWhen.parseAll(List.of("goal_met")))
        .containsExactly(StopWhen.State.GOAL_MET, new StopWhen.Cap(StopWhen.DEFAULT_CAP));
    assertThat(StopWhen.parseAll(List.of("goal_met", "3 turns")))
        .containsExactly(StopWhen.State.GOAL_MET, new StopWhen.Cap(3));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {
        "Ok, understood. I'll leave it there, but let me know. | true",
        "No worries, all the best. | true",
        "Take care. | true",
        "Bye for now. | true",
        "Does Monday suit? | false",
        "Maybe by Friday? | false"
      })
  void metOnGoodbyeRegex(String reply, boolean signsOff) {
    assertThat(new StopWhen.Goodbye().holds(new Conversation(), 2, reply)).isEqualTo(signsOff);
  }

  @Test
  void metOnSilence() {
    assertThat(new StopWhen.Silence().holds(new Conversation(), 1, null)).isTrue();
    assertThat(new StopWhen.Goodbye().holds(new Conversation(), 1, null)).isFalse();
  }

  /** The first condition that holds is the stop reason. */
  @Test
  void firstMetIsInListOrder() {
    Conversation handedOff = new Conversation();
    handedOff.needsHuman = true;
    List<StopWhen.Condition> conditions =
        List.of(StopWhen.State.GOAL_MET, StopWhen.State.NEEDS_HUMAN, new StopWhen.Cap(3));

    assertThat(StopWhen.firstMet(conditions, handedOff, 3, "Ok"))
        .contains(StopWhen.State.NEEDS_HUMAN);
    assertThat(StopWhen.firstMet(conditions, new Conversation(), 3, "Ok"))
        .contains(new StopWhen.Cap(3));
    assertThat(StopWhen.firstMet(conditions, new Conversation(), 2, "Ok")).isEmpty();
  }
}
