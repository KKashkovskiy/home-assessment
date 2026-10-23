package com.enrola.anna.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.Decisions;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.ObjectionType;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.guard.PreGuards;
import com.enrola.anna.prompt.Template;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The counting rules with limits 15/3/1, one decision at a time, no database. */
class TurnCountersTest {

  final TurnCounters counters = new TurnCounters(SettingsFixture.DEFAULTS.limits());
  final Conversation conversation = new Conversation();
  final List<Message> transcript = new ArrayList<>();

  /** Busy with a day named is a deferral; anything else that objects counts. */
  @ParameterizedTest(name = "{0}, day {1}: {2}")
  @CsvSource({"BUSY, 2026-07-29, 0", "BUSY, , 1", "NOT_INTERESTED, 2026-07-29, 1"})
  void objectionsCountUnlessDeferred(ObjectionType type, String preferredDay, int counted) {
    Decision objection =
        Decisions.engaged()
            .objection(type)
            .extracted(
                new Decision.Extracted(
                    Decision.CoverageGoal.UNKNOWN,
                    Decision.CoverType.UNKNOWN,
                    null,
                    preferredDay,
                    Decision.TimeOfDay.UNKNOWN))
            .build();

    count("not now", objection);

    assertThat(conversation.objectionsUsed).isEqualTo(counted);
  }

  /**
   * After one off-topic turn: off topic grows the streak, engagement resets it, the rest keep it.
   */
  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource({
    "OFF_TOPIC,       2",
    "INJECTION,       2",
    "ENGAGED,         0",
    "QUESTION,        0",
    "OBJECTION,       0",
    "AI_QUESTION,     0",
    "PLEASANTRY,      0",
    "HANDOFF_REQUEST, 0",
    "RESCHEDULE,      0",
    "DISTRESS,        1",
    "OTHER,           1"
  })
  void streakAfterAnOffTopicTurn(LeadIntent intent, int streak) {
    count("what's the weather", Decisions.engaged().intent(LeadIntent.OFF_TOPIC).build());

    count("something else", Decisions.engaged().intent(intent).build());

    assertThat(conversation.offTopicStreak).isEqualTo(streak);
  }

  /** A repeat adds one after the reset, so an engaged lead repeating "Yes" never reaches three. */
  @Test
  void repeatedYesAddsOneAndNoMore() {
    List<Integer> streaks = new ArrayList<>();
    for (String text : List.of("save money", "Yes", "yes!", "YES", "Yes")) {
      assertThat(count(text, Decisions.engaged().build())).isNull();
      streaks.add(conversation.offTopicStreak);
    }

    assertThat(streaks).containsExactly(0, 0, 1, 1, 1);
  }

  @Test
  void offTopicLimitForcesTheOffTopicClose() {
    conversation.offTopicStreak = 2;

    assertThat(count("tell me a joke", Decisions.engaged().intent(LeadIntent.OFF_TOPIC).build()))
        .isEqualTo(new PreGuards.FixedClose(Template.OFF_TOPIC_CLOSE, EndReason.OFF_TOPIC_LIMIT));
  }

  @Test
  void secondObjectionForcesTheDeclinedClose() {
    Decision notInterested = Decisions.engaged().objection(ObjectionType.NOT_INTERESTED).build();

    assertThat(count("not interested", notInterested)).isNull();
    assertThat(count("still not interested", notInterested))
        .isEqualTo(new PreGuards.FixedClose(Template.DECLINED_CLOSE, EndReason.DECLINED));
  }

  /** Adds the lead's {@code text} to the transcript, counts the turn, returns the close or null. */
  private PreGuards.FixedClose count(String text, Decision decision) {
    transcript.add(Message.inbound("c-test", transcript.size() + 1, text, null, Instant.EPOCH));
    return counters.count(conversation, decision, transcript).orElse(null);
  }
}
