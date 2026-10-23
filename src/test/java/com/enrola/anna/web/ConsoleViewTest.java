package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.conversation.ConversationStatus;
import com.enrola.anna.web.ConsoleView.StepState;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The stage tracker's marks and the composer's hint, from the conversation's state alone. */
class ConsoleViewTest {

  /** Stages the decisions named, by initial: E P C O B. */
  @ParameterizedTest(name = "at {0} after {1}: {2}")
  @CsvSource({
    "ENGAGE, E, CURRENT AHEAD AHEAD AHEAD AHEAD",
    "OFFER_TIMES, EPCO, DONE DONE DONE CURRENT AHEAD",
    "OFFER_TIMES, O, DONE SKIPPED SKIPPED CURRENT AHEAD",
    "BOOKED, EPO, DONE DONE SKIPPED DONE CURRENT"
  })
  void trackerMarksStagesNoDecisionNamedAsSkipped(Stage current, String seen, String expected) {
    ConsoleView view = view(open(current), seenFrom(seen));

    assertThat(view.stages()).extracting(ConsoleView.Step::state).containsExactly(states(expected));
  }

  @ParameterizedTest(name = "{0} {1} needs human {2}: {3}")
  @CsvSource({
    "ACTIVE, NONE, false, READY",
    "HANDOFF, NONE, true, HANDED_OFF",
    "DECLINED, DECLINED, false, CLOSING_ONLY",
    "BOOKED, BOOKED, true, CLOSING_ONLY",
    "ABUSIVE, ABUSIVE, false, SILENT",
    "UNSUBSCRIBED, UNSUBSCRIBED, false, SILENT",
    "RESET, NONE, false, SILENT"
  })
  void hintSaysWhatTheNextMessageGets(
      ConversationStatus status, EndReason endReason, boolean needsHuman, ComposerHint hint) {
    assertThat(view(conversation(status, endReason, needsHuman), Set.of()).hint()).isEqualTo(hint);
  }

  /** Goal met, unsubscribed, ended, needs human: an end warns unless it is the booking. */
  @ParameterizedTest(name = "{0} {1}: {3}")
  @CsvSource({
    "ACTIVE, NONE, false, PLAIN PLAIN PLAIN PLAIN",
    "HANDOFF, NONE, true, PLAIN PLAIN PLAIN WARN",
    "DECLINED, DECLINED, false, PLAIN PLAIN WARN PLAIN",
    "BOOKED, BOOKED, false, PASS PLAIN PLAIN PLAIN",
    "UNSUBSCRIBED, UNSUBSCRIBED, false, PLAIN WARN WARN PLAIN"
  })
  void badgesWarnOnlyWhereAPersonShouldLook(
      ConversationStatus status, EndReason endReason, boolean needsHuman, String tones) {
    Conversation conversation = conversation(status, endReason, needsHuman);
    conversation.goalMet = status == ConversationStatus.BOOKED;
    conversation.unsubscribed = status == ConversationStatus.UNSUBSCRIBED;

    assertThat(view(conversation, Set.of()).badges())
        .extracting(ConsoleView.Badge::tone)
        .containsExactly(
            List.of(tones.split(" ")).stream()
                .map(ConsoleView.Tone::valueOf)
                .toArray(ConsoleView.Tone[]::new));
  }

  private static Conversation conversation(
      ConversationStatus status, EndReason endReason, boolean needsHuman) {
    Conversation conversation = open(Stage.ENGAGE);
    conversation.status = status;
    conversation.endReason = endReason;
    conversation.ended =
        status != ConversationStatus.ACTIVE && status != ConversationStatus.HANDOFF;
    conversation.needsHuman = needsHuman;
    conversation.handoffReason = needsHuman ? HandoffReason.ERROR : HandoffReason.NONE;
    return conversation;
  }

  private static Conversation open(Stage stage) {
    Conversation conversation = new Conversation();
    conversation.stage = stage;
    conversation.status = ConversationStatus.ACTIVE;
    conversation.endReason = EndReason.NONE;
    conversation.handoffReason = HandoffReason.NONE;
    return conversation;
  }

  private static ConsoleView view(Conversation conversation, Set<Stage> seen) {
    return new ConsoleView(
        conversation, null, null, List.of(), seen, List.of(), null, null, null, 1600);
  }

  private static Set<Stage> seenFrom(String initials) {
    Set<Stage> seen = EnumSet.noneOf(Stage.class);
    for (Stage stage : Stage.values()) {
      if (initials.indexOf(stage.name().charAt(0)) >= 0) {
        seen.add(stage);
      }
    }
    return seen;
  }

  private static StepState[] states(String names) {
    return List.of(names.split(" ")).stream().map(StepState::valueOf).toArray(StepState[]::new);
  }
}
