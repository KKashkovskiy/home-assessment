package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.conversation.Conversation;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Replies count after the goal or the end only on a turn that began past it, so the confirmation
 * and the closing template are free and the probes are what is measured.
 */
class StoryCountersTest {

  /** One observed turn: the state after it and how many replies it sent. */
  record Observed(boolean goalMet, boolean ended, int replies) {

    Conversation after() {
      var conversation = new Conversation();
      conversation.goalMet = goalMet;
      conversation.ended = ended;
      return conversation;
    }
  }

  static List<Arguments> turns() {
    Observed open = new Observed(false, false, 1);
    Observed books = new Observed(true, true, 1);
    Observed closes = new Observed(false, true, 1);
    return List.of(
        Arguments.of("the closing turn itself", List.of(open, closes), 0, 0),
        Arguments.of(
            "one reply after the end", List.of(closes, new Observed(false, true, 1)), 0, 1),
        Arguments.of("silence after the end", List.of(closes, new Observed(false, true, 0)), 0, 0),
        Arguments.of("the confirmation itself", List.of(open, books), 0, 0),
        Arguments.of(
            "one reply after the goal", List.of(books, new Observed(true, true, 1)), 1, 1));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("turns")
  void countsRepliesOnlyAfterTheTurnThatGotThere(
      String name, List<Observed> observed, int afterGoal, int afterEnd) {
    var counters = new StoryCounters();

    observed.forEach(turn -> counters.observe(turn.after(), turn.replies()));

    assertThat(counters.afterGoal()).isEqualTo(afterGoal);
    assertThat(counters.afterEnd()).isEqualTo(afterEnd);
  }

  /** The objection that closed DECLINED was not retried. */
  @ParameterizedTest(name = "{0} objections, {1}")
  @MethodSource("objections")
  void retriesLeaveOutTheObjectionThatDeclined(int objections, EndReason end, int retries) {
    var conversation = new Conversation();
    conversation.objectionsUsed = objections;
    conversation.endReason = end;
    var counters = new StoryCounters();

    counters.observe(conversation, 1);

    assertThat(counters.retries()).isEqualTo(retries);
  }

  static List<Arguments> objections() {
    return List.of(
        Arguments.of(1, EndReason.NONE, 1),
        Arguments.of(2, EndReason.DECLINED, 1),
        Arguments.of(0, EndReason.NONE, 0));
  }
}
