package com.enrola.anna.eval;

import static com.enrola.anna.eval.Simulator.Moment.AFTER_END;
import static com.enrola.anna.eval.Simulator.Moment.TALKING;
import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.config.Settings.CalendarPreset;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Which rubrics apply to a scenario, and which questions a trial gets from them. */
class JudgingTest {

  static final Rubric KNOWLEDGE = ScenarioFixtures.rubric("J-KB", Rubric.Scope.TURN, "knowledge");
  static final Rubric ABUSE = ScenarioFixtures.rubric("J-ABUSE", Rubric.Scope.TURN, "abuse");
  static final Rubric CLOSE =
      ScenarioFixtures.rubric("J-CLOSE", Rubric.Scope.TRANSCRIPT, "knowledge");

  static final Scenario.Turn TURN =
      ScenarioFixtures.turn(
          "turn-x", "how are you paid?", Scenario.Expect.NONE, "core", "knowledge");

  @Test
  void appliesByTag() {
    assertThat(Judging.applicable(TURN, List.of(KNOWLEDGE, ABUSE))).containsExactly(KNOWLEDGE);
  }

  @Test
  void ownQuestionWrapsAsTurnRubricInATurnScenario() {
    Rubric own = Judging.adHoc(TURN, 0, "Does it answer?");

    assertThat(own.id()).isEqualTo("Q-turn-x-0");
    assertThat(own.scope()).isEqualTo(Rubric.Scope.TURN);
    assertThat(own.question()).isEqualTo("Does it answer?");
  }

  /** A story's own questions span the conversation, so they are asked once about the transcript. */
  @Test
  void ownQuestionWrapsAsTranscriptRubricInAStory() {
    assertThat(Judging.adHoc(story(), 1, "Does it book?").scope())
        .isEqualTo(Rubric.Scope.TRANSCRIPT);
  }

  @Test
  void ownQuestionsAlwaysApply() {
    assertThat(Judging.applicable(story(), List.of(ABUSE)))
        .extracting(Rubric::id)
        .containsExactly("Q-story-x-0");
  }

  @Test
  void aTurnTrialIsJudgedAtItsTurnAndAsAWhole() {
    Trial trial = turnTrial(TrialStatus.FAIL, "We get a referral fee.");

    assertThat(Judging.questions(TURN, trial, List.of(KNOWLEDGE, CLOSE)))
        .containsExactly(new Judging.Question(KNOWLEDGE, 2), new Judging.Question(CLOSE, null));
  }

  /** A turn the product answered with silence has no reply for a turn rubric to mark. */
  @Test
  void aSilentTurnGetsNoTurnQuestion() {
    assertThat(Judging.questions(TURN, turnTrial(TrialStatus.PASS, null), List.of(KNOWLEDGE)))
        .isEmpty();
  }

  /** The opener is a template and a silent probe sent nothing, so neither is judged. */
  @Test
  void aStoryIsJudgedAtEveryTurnThatReplied() {
    Scenario.Story story = story();
    Trial trial =
        trial(
            story,
            TrialStatus.PASS,
            new Trial.StoryDetail(
                List.of(
                    turn(0, null, "Hi Jane, it's Anna.", TALKING),
                    turn(1, "how are you paid?", "We get a referral fee. Free Monday?", TALKING),
                    turn(2, "12", "Booked for Mon 27 Jul 12:00 PM AEST.", TALKING),
                    turn(3, "thanks", null, AFTER_END)),
                "goal_met after 2 lead messages"));

    assertThat(Judging.questions(story, trial, List.of(KNOWLEDGE, CLOSE)))
        .extracting(Judging.Question::turnNo)
        .containsExactly(1, 2, null, null);
  }

  /** INVALID and cut-short trials say nothing about Anna, so they get no question. */
  @ParameterizedTest
  @EnumSource(
      value = TrialStatus.class,
      names = {"INVALID", "SKIPPED_BUDGET", "SKIPPED_NO_KEY", "SKIPPED_KIND"})
  void onlyAVerdictIsJudged(TrialStatus status) {
    assertThat(Judging.questions(TURN, turnTrial(status, "Hi"), List.of(KNOWLEDGE))).isEmpty();
  }

  /** A transcript rubric needs no replied turn, so only the missing conversation stops it. */
  @Test
  void aTrialWithNoConversationIsNotJudged() {
    Trial fault = Trial.scenarioFault(TURN, 1, "no seeded lead");

    assertThat(Judging.questions(TURN, fault, List.of(CLOSE))).isEmpty();
  }

  /** Six lead messages for the turn rubric, one for the transcript rubric, one own question. */
  @Test
  void callsCountAStorysTurnRubricsAtItsCap() {
    assertThat(Judging.calls(story(), List.of(KNOWLEDGE, CLOSE))).isEqualTo(8);
    assertThat(Judging.calls(TURN, List.of(KNOWLEDGE, CLOSE, ABUSE))).isEqualTo(2);
  }

  private static Trial turnTrial(TrialStatus status, String reply) {
    return trial(TURN, status, new Trial.TurnDetail(2, reply, List.of()));
  }

  private static Trial trial(Scenario scenario, TrialStatus status, Trial.Detail detail) {
    return new Trial(
        scenario.id(),
        1,
        status,
        List.of(),
        List.of(),
        BigDecimal.ZERO,
        1,
        "c-" + scenario.id(),
        detail,
        0);
  }

  private static StoryTurn turn(int turnNo, String lead, String reply, Simulator.Moment moment) {
    return new StoryTurn(
        turnNo,
        lead,
        reply == null ? List.of() : List.of(reply),
        List.of(),
        BigDecimal.ZERO,
        moment);
  }

  /** Jane, tagged knowledge, capped at six lead messages, with one question of its own. */
  private static Scenario.Story story() {
    return new Scenario.Story(
        "story-x",
        "Title of story-x",
        List.of("knowledge"),
        new Scenario.LeadRef("jane", null),
        ScenarioFixtures.SUNDAY,
        CalendarPreset.NORMAL,
        "Asks how Comparato is paid.",
        List.of("goal_met", "6 lead messages have been sent"),
        Scenario.Expect.NONE,
        List.of("Does it book?"),
        null);
  }
}
