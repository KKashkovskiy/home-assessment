package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.conversation.Conversation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** When each stop condition holds after a turn. */
class StopConditionsTest {

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
    assertThat(StopConditions.holds(new StopWhen.Goodbye(), new Conversation(), 2, reply))
        .isEqualTo(signsOff);
  }

  @Test
  void metOnSilence() {
    assertThat(StopConditions.holds(new StopWhen.Silence(), new Conversation(), 1, null)).isTrue();
    assertThat(StopConditions.holds(new StopWhen.Goodbye(), new Conversation(), 1, null)).isFalse();
  }

  /** The first condition that holds is the stop reason. */
  @Test
  void firstMetIsInListOrder() {
    Conversation handedOff = new Conversation();
    handedOff.needsHuman = true;
    List<StopWhen.Condition> conditions =
        List.of(StopWhen.State.GOAL_MET, StopWhen.State.NEEDS_HUMAN, new StopWhen.Cap(3));

    assertThat(StopConditions.firstMet(conditions, handedOff, 3, "Ok"))
        .contains(StopWhen.State.NEEDS_HUMAN);
    assertThat(StopConditions.firstMet(conditions, new Conversation(), 3, "Ok"))
        .contains(new StopWhen.Cap(3));
    assertThat(StopConditions.firstMet(conditions, new Conversation(), 2, "Ok")).isEmpty();
  }
}
