package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.conversation.Message;
import com.enrola.anna.conversation.MessageSource;
import com.enrola.anna.lead.Lead;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The transcript as the judge reads it, and what counts as a quote from it. */
class TrialTranscriptTest {

  static final String OFFER =
      "Monday I've got 12:00 PM, 12:30 or 1:00 PM. Would one of those work?";

  /** Lauren at turn 2 of a turn scenario, with a phone and an email the judge must never see. */
  static final TrialTranscript LAUREN =
      new TrialTranscript(
          ScenarioFixtures.turn("turn-x", "monday lunchtime?", Scenario.Expect.NONE, "core"),
          1,
          new Lead(
              "turn-x",
              "Lauren",
              "+61491570156",
              "lauren@example.test",
              "VIC",
              "Bupa",
              null,
              null,
              null,
              Instant.EPOCH),
          List.of(
              out(0, "Hi Lauren, it's Anna from Comparato."),
              in(1, "save money"),
              out(1, "Makes sense. Do you know what you're covered for?"),
              in(2, "hospital mainly. monday lunchtime?"),
              out(2, OFFER)));

  @Test
  void rendersOneLabelledLinePerMessageWithTheTurnMarked() {
    assertThat(LAUREN.render(2))
        .isEqualTo(
            """
            [turn 0] Anna: Hi Lauren, it's Anna from Comparato.
            [turn 1] Lead: save money
            [turn 1] Anna: Makes sense. Do you know what you're covered for?
            [turn 2] Lead: hospital mainly. monday lunchtime?
            [turn 2] Anna: [UNDER JUDGEMENT] %s"""
                .formatted(OFFER));
  }

  @Test
  void aTranscriptQuestionMarksNoTurn() {
    assertThat(LAUREN.render(null)).doesNotContain(TrialTranscript.UNDER_JUDGEMENT);
  }

  /** Only an SMS text says something: labels, markers and spans over two messages do not. */
  @Test
  void saysOnlyWhatOneMessageSays() {
    assertThat(LAUREN.says("Would one of those work?")).isTrue();
    assertThat(LAUREN.says("Anna: Makes sense.")).isFalse();
    assertThat(LAUREN.says("covered for?\n[turn 2] Lead: hospital")).isFalse();
  }

  static Message out(int turnNo, String text) {
    return Message.outbound("c-1", turnNo, text, MessageSource.LLM, Instant.EPOCH);
  }

  static Message in(int turnNo, String text) {
    return Message.inbound("c-1", turnNo, text, "p-" + turnNo, Instant.EPOCH);
  }
}
