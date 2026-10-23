package com.enrola.anna.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Terminal precedence and handoff stickiness on a fresh, open conversation. */
class StateRulesTest {

  static final Instant T1 = Instant.parse("2026-07-26T00:00:00Z");
  static final Instant T2 = Instant.parse("2026-07-26T00:01:00Z");

  final Conversation conversation = open();

  private static Conversation open() {
    Conversation conversation = new Conversation();
    conversation.id = "c-test";
    conversation.status = ConversationStatus.ACTIVE;
    conversation.endReason = EndReason.NONE;
    conversation.handoffReason = HandoffReason.NONE;
    conversation.setEndReasonHistory(List.of());
    return conversation;
  }

  @Test
  void endSetsReasonStatusAndHistory() {
    StateRules.end(conversation, EndReason.DECLINED, T1);

    assertThat(conversation.ended).isTrue();
    assertThat(conversation.endReason).isEqualTo(EndReason.DECLINED);
    assertThat(conversation.status).isEqualTo(ConversationStatus.DECLINED);
    assertThat(conversation.endReasonHistory())
        .containsExactly(new EndReasonEntry(EndReason.DECLINED, T1));
  }

  /** Only an outranking reason replaces the current one, and only a replacement is recorded. */
  @ParameterizedTest(name = "{0} then {1} ends as {2}")
  @CsvSource({
    "DECLINED,     ABUSIVE,      ABUSIVE,      2",
    "ABUSIVE,      DECLINED,     ABUSIVE,      1",
    "ABUSIVE,      SAFETY,       SAFETY,       2",
    "SAFETY,       ABUSIVE,      SAFETY,       1",
    "SAFETY,       UNSUBSCRIBED, UNSUBSCRIBED, 2",
    "UNSUBSCRIBED, SAFETY,       UNSUBSCRIBED, 1",
    "BOOKED,       DECLINED,     BOOKED,       1",
    "MAX_TURNS,    UNSUBSCRIBED, UNSUBSCRIBED, 2"
  })
  void endMovesOnlyUpward(EndReason first, EndReason second, EndReason expected, int transitions) {
    StateRules.end(conversation, first, T1);
    StateRules.end(conversation, second, T2);

    assertThat(conversation.endReason).isEqualTo(expected);
    assertThat(conversation.status.name()).isEqualTo(expected.name());
    assertThat(conversation.endReasonHistory()).hasSize(transitions);
  }

  @Test
  void unsubscribedIsSticky() {
    StateRules.end(conversation, EndReason.UNSUBSCRIBED, T1);
    StateRules.end(conversation, EndReason.SAFETY, T2);

    assertThat(conversation.unsubscribed).isTrue();
    assertThat(conversation.endReason).isEqualTo(EndReason.UNSUBSCRIBED);
  }

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
    assertThat(StateRules.isHardTerminal(reason)).isEqualTo(hardTerminal);
  }

  @Test
  void endNoneIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> StateRules.end(conversation, EndReason.NONE, T1));
  }

  @Test
  void handoffIsStickyWithLatestReason() {
    StateRules.handoff(conversation, HandoffReason.REQUESTED);
    StateRules.handoff(conversation, HandoffReason.ERROR);

    assertThat(conversation.needsHuman).isTrue();
    assertThat(conversation.handoffReason).isEqualTo(HandoffReason.ERROR);
    assertThat(conversation.status).isEqualTo(ConversationStatus.HANDOFF);
    assertThat(conversation.ended).isFalse();
  }

  @Test
  void handoffKeepsAnEndedStatus() {
    StateRules.end(conversation, EndReason.DECLINED, T1);
    StateRules.handoff(conversation, HandoffReason.COMPLAINT);

    assertThat(conversation.needsHuman).isTrue();
    assertThat(conversation.status).isEqualTo(ConversationStatus.DECLINED);
  }

  @Test
  void handoffAfterUnsubscribeIsIgnored() {
    StateRules.end(conversation, EndReason.UNSUBSCRIBED, T1);
    StateRules.handoff(conversation, HandoffReason.ERROR);

    assertThat(conversation.needsHuman).isFalse();
    assertThat(conversation.handoffReason).isEqualTo(HandoffReason.NONE);
  }

  @Test
  void handoffNoneIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> StateRules.handoff(conversation, HandoffReason.NONE));
  }
}
