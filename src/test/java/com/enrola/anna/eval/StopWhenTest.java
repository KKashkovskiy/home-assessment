package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The stop_when grammar. */
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
}
