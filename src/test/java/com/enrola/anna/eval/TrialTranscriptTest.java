package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.conversation.Message;
import com.enrola.anna.conversation.MessageSource;
import com.enrola.anna.lead.Lead;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The transcript as the judge reads it, who wrote each SMS, and what counts as a quote from it. */
class TrialTranscriptTest {

  static final String OFFER =
      "Monday I've got 12:00 PM, 12:30 or 1:00 PM. Would one of those work?";

  /**
   * Lauren at turn 2 of a turn scenario, after its seeded history, with a phone and an email the
   * judge must never see.
   */
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
              template(0, "Hi Lauren, it's Anna from Comparato."),
              in(1, "save money"),
              out(1, "Makes sense. Do you know what you're covered for?"),
              in(2, "hospital mainly. monday lunchtime?"),
              out(2, OFFER)));

  /** A story whose opener and closing the code wrote, around one reply of the model. */
  static final TrialTranscript STORY =
      new TrialTranscript(
          ScenarioFixtures.story("story-x"),
          1,
          LAUREN.lead(),
          List.of(
              template(0, "Hi Lauren, it's Anna from Comparato."),
              in(1, "tell me a joke"),
              out(1, "That's outside what I can help with here. Saving money or better cover?"),
              in(2, "a poem then"),
              fixed(2, "I'll leave it there for now.")));

  /** The seeded history is the scenario's, whatever source its rows carry. */
  @Test
  void rendersOneLabelledLinePerMessageWithTheTurnMarked() {
    assertThat(LAUREN.render(2))
        .isEqualTo(
            """
            [turn 0] Anna (scenario history): Hi Lauren, it's Anna from Comparato.
            [turn 1] Lead: save money
            [turn 1] Anna (scenario history): Makes sense. Do you know what you're covered for?
            [turn 2] Lead: hospital mainly. monday lunchtime?
            [turn 2] Anna: [UNDER JUDGEMENT] %s"""
                .formatted(OFFER));
  }

  @Test
  void labelsWhatTheCodeWroteInAStory() {
    assertThat(STORY.render(null))
        .isEqualTo(
            """
            [turn 0] Anna (fixed text): Hi Lauren, it's Anna from Comparato.
            [turn 1] Lead: tell me a joke
            [turn 1] Anna: That's outside what I can help with here. Saving money or better cover?
            [turn 2] Lead: a poem then
            [turn 2] Anna (fixed text): I'll leave it there for now.""");
  }

  @Test
  void aTranscriptQuestionMarksNoTurn() {
    assertThat(LAUREN.render(null)).doesNotContain(TrialTranscript.UNDER_JUDGEMENT);
  }

  /**
   * Only the text of an SMS the lead or the model wrote says something: labels, markers, spans over
   * two messages, scenario history and fixed text do not.
   */
  @Test
  void saysOnlyWhatOneMessageSays() {
    assertThat(LAUREN.says("Would one of those work?")).isTrue();
    assertThat(LAUREN.says("hospital mainly")).isTrue();
    assertThat(LAUREN.says("Anna: Makes sense.")).isFalse();
    assertThat(LAUREN.says("covered for?\n[turn 2] Lead: hospital")).isFalse();
    assertThat(LAUREN.says("Do you know what you're covered for?")).isFalse();
    assertThat(STORY.says("outside what I can help with")).isTrue();
    assertThat(STORY.says("I'll leave it there")).isFalse();
  }

  @Test
  void modelRepliedOnlyWhereTheModelWroteInTheTrial() {
    assertThat(STORY.modelReplied(1)).isTrue();
    assertThat(STORY.modelReplied(2)).isFalse();
    assertThat(LAUREN.modelReplied(1)).isFalse();
    assertThat(LAUREN.modelReplied(2)).isTrue();
  }

  static Message out(int turnNo, String text) {
    return Message.outbound("c-1", turnNo, text, MessageSource.LLM, Instant.EPOCH);
  }

  static Message template(int turnNo, String text) {
    return Message.outbound("c-1", turnNo, text, MessageSource.TEMPLATE, Instant.EPOCH);
  }

  static Message fixed(int turnNo, String text) {
    return Message.outbound("c-1", turnNo, text, MessageSource.FIXED, Instant.EPOCH);
  }

  static Message in(int turnNo, String text) {
    return Message.inbound("c-1", turnNo, text, "p-" + turnNo, Instant.EPOCH);
  }
}
