package com.enrola.anna.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.Stage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Terminal precedence, handoff stickiness, the goal, safety after an end and the closing-reply
 * rule, from a fresh, open conversation.
 */
class StateRulesTest {

  static final Instant T1 = Instant.parse("2026-07-26T00:00:00Z");
  static final Instant T2 = Instant.parse("2026-07-26T00:01:00Z");

  final Conversation conversation = open();

  private static Conversation open() {
    Conversation conversation = new Conversation();
    conversation.id = "c-test";
    conversation.status = ConversationStatus.ACTIVE;
    conversation.stage = Stage.OFFER_TIMES;
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
  void bookedSetsGoalStageAndEnd() {
    StateRules.booked(conversation, T1);

    assertThat(conversation.goalMet).isTrue();
    assertThat(conversation.stage).isEqualTo(Stage.BOOKED);
    assertThat(conversation.endReason).isEqualTo(EndReason.BOOKED);
    assertThat(conversation.status).isEqualTo(ConversationStatus.BOOKED);
    assertThat(conversation.endReasonHistory())
        .containsExactly(new EndReasonEntry(EndReason.BOOKED, T1));
  }

  @Test
  void bookedIsNoOpAfterEnd() {
    StateRules.end(conversation, EndReason.DECLINED, T1);

    StateRules.booked(conversation, T2);

    assertThat(conversation.goalMet).isFalse();
    assertThat(conversation.stage).isEqualTo(Stage.OFFER_TIMES);
    assertThat(conversation.endReason).isEqualTo(EndReason.DECLINED);
  }

  /** The booking stays and a human cancels the call: the handoff is set before the opt-out. */
  @ParameterizedTest
  @CsvSource({"UNSUBSCRIBED", "ABUSIVE"})
  void optOutOrAbuseAfterBookingAddsReview(EndReason reason) {
    StateRules.booked(conversation, T1);

    StateRules.end(conversation, reason, T2);

    assertThat(conversation.endReason).isEqualTo(reason);
    assertThat(conversation.goalMet).isTrue();
    assertThat(conversation.needsHuman).isTrue();
    assertThat(conversation.handoffReason).isEqualTo(HandoffReason.BOOKING_REVIEW);
  }

  @Test
  void optOutWithoutBookingNeedsNoReview() {
    StateRules.end(conversation, EndReason.UNSUBSCRIBED, T1);

    assertThat(conversation.needsHuman).isFalse();
  }

  @Test
  void safetyOnAnOpenConversationEndsSafetyAndHandsOff() {
    StateRules.safety(conversation, T1);

    assertThat(conversation.endReason).isEqualTo(EndReason.SAFETY);
    assertThat(conversation.needsHuman).isTrue();
    assertThat(conversation.handoffReason).isEqualTo(HandoffReason.SELF_HARM);
  }

  /** Safety beats silence: an opted-out lead is still handed off, and stays opted out. */
  @Test
  void safetyAfterStopKeepsTheEndAndHandsOff() {
    StateRules.end(conversation, EndReason.UNSUBSCRIBED, T1);

    StateRules.safety(conversation, T2);

    assertThat(conversation.endReason).isEqualTo(EndReason.UNSUBSCRIBED);
    assertThat(conversation.unsubscribed).isTrue();
    assertThat(conversation.status).isEqualTo(ConversationStatus.UNSUBSCRIBED);
    assertThat(conversation.endReasonHistory())
        .containsExactly(
            new EndReasonEntry(EndReason.UNSUBSCRIBED, T1),
            new EndReasonEntry(EndReason.SAFETY, T2));
    assertThat(conversation.needsHuman).isTrue();
    assertThat(conversation.handoffReason).isEqualTo(HandoffReason.SELF_HARM);
  }

  @Test
  void safetyAfterASoftEndEndsSafety() {
    StateRules.end(conversation, EndReason.DECLINED, T1);

    StateRules.safety(conversation, T2);

    assertThat(conversation.endReason).isEqualTo(EndReason.SAFETY);
    assertThat(conversation.handoffReason).isEqualTo(HandoffReason.SELF_HARM);
  }

  @ParameterizedTest(name = "{1} chars, \"?\" {2}, {0} sent before: {3}")
  @CsvSource({
    "0, 160, false, true",
    "0, 161, false, false",
    "0, 120, true,  false",
    "1, 20,  false, false"
  })
  void closingReplyIsOneShortStatement(
      int sentBefore, int length, boolean question, boolean allowed) {
    conversation.postTerminalReplies = sentBefore;
    String reply = question ? "?".repeat(length) : "x".repeat(length);

    assertThat(StateRules.closingReplyAllowed(conversation, reply)).isEqualTo(allowed);
  }

  @Test
  void handoffNoneIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> StateRules.handoff(conversation, HandoffReason.NONE));
  }
}
