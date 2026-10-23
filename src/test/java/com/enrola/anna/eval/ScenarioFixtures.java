package com.enrola.anna.eval;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.PriceTable;
import com.enrola.anna.config.Settings.CalendarPreset;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.conversation.TurnStatus;
import com.enrola.anna.eval.Scenario.Expect;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** Scenarios for tests: the files on disk, or built in memory on the seed set's Sunday clock. */
final class ScenarioFixtures {

  static final OffsetDateTime SUNDAY = OffsetDateTime.parse("2026-07-26T10:00:00+10:00");

  private ScenarioFixtures() {}

  /** The turn scenario file {@code id}. */
  static Scenario.Turn load(String id) {
    return (Scenario.Turn)
        new ScenarioLoader().load(ScenarioLoader.SCENARIOS.resolve(id + ".yaml"));
  }

  /** The story scenario file {@code id}. */
  static Scenario.Story loadStory(String id) {
    return (Scenario.Story)
        new ScenarioLoader().load(ScenarioLoader.SCENARIOS.resolve(id + ".yaml"));
  }

  /** Lauren on the normal calendar, the opener only, then {@code message}. */
  static Scenario.Turn turn(String id, String message, Expect expect, String... tags) {
    return new Scenario.Turn(
        id,
        "Title of " + id,
        List.of(tags),
        new Scenario.LeadRef("lauren", null),
        SUNDAY,
        CalendarPreset.NORMAL,
        null,
        List.of(new Scenario.History("Hi Lauren, it's Anna.", null, null)),
        message,
        expect,
        null,
        null);
  }

  static Scenario.Story story(String id, String... tags) {
    return new Scenario.Story(
        id,
        "Title of " + id,
        List.of(tags),
        new Scenario.LeadRef("lauren", null),
        SUNDAY,
        CalendarPreset.NORMAL,
        "Books Monday morning.",
        List.of("goal_met", "8 lead messages have been sent"),
        Expect.NONE,
        null,
        null);
  }

  /** A trial of {@code scenario} that ended {@code status} with {@code checks}, at no cost. */
  static Trial trial(Scenario scenario, int trialNo, TrialStatus status, Check... checks) {
    return new Trial(
        scenario.id(),
        trialNo,
        status,
        List.of(checks),
        List.of(),
        BigDecimal.ZERO,
        1,
        "c-" + scenario.id(),
        switch (scenario) {
          case Scenario.Turn _ -> Trial.TurnDetail.NONE;
          case Scenario.Story _ -> Trial.StoryDetail.NONE;
        },
        0);
  }

  /** {@code name} at the default settings' prices, which know gpt-5.1 only. */
  static EvalModels.Model model(String name) {
    return new EvalModels.Model(name, new PriceTable(SettingsFixture.DEFAULTS.priceTable()));
  }

  /** A rubric on {@code tags} asking whether {@code id} holds, with one example each way. */
  static Rubric rubric(String id, Rubric.Scope scope, String... tags) {
    return new Rubric(
        id,
        "Does " + id + " hold?",
        scope,
        Rubric.Severity.SOFT,
        Set.of(tags),
        null,
        new Rubric.Examples(List.of("yes"), List.of("no")));
  }

  /** The judge's answer to {@code question} about {@code trial}, quoting "hi", at {@code cost}. */
  static JudgeResult answer(
      Judging.Question question, Trial trial, JudgeResult.Verdict verdict, String cost) {
    return new JudgeResult(
        question.rubric().id(),
        trial.scenarioId(),
        trial.trialNo(),
        question.turnNo(),
        verdict,
        verdict == JudgeResult.Verdict.PASS || verdict == JudgeResult.Verdict.FAIL ? "hi" : null,
        "scripted",
        new BigDecimal(cost),
        5);
  }

  static ExpectBuilder expect() {
    return new ExpectBuilder();
  }

  /** The expectations tests set, over the all-nullable record. */
  static final class ExpectBuilder {
    private LeadIntent intent;
    private List<Flag> flags;
    private EndReason endReason;
    private Boolean conversationEnded;
    private Boolean modelCalled;
    private Boolean replySent;
    private List<String> matches;
    private List<String> notMatches;
    private Boolean endsWithQuestion;
    private List<String> tools;
    private TurnStatus turnStatus;

    ExpectBuilder intent(LeadIntent value) {
      intent = value;
      return this;
    }

    ExpectBuilder flags(Flag... values) {
      flags = List.of(values);
      return this;
    }

    ExpectBuilder endReason(EndReason value) {
      endReason = value;
      return this;
    }

    ExpectBuilder conversationEnded(boolean value) {
      conversationEnded = value;
      return this;
    }

    ExpectBuilder modelCalled(boolean value) {
      modelCalled = value;
      return this;
    }

    ExpectBuilder replySent(boolean value) {
      replySent = value;
      return this;
    }

    ExpectBuilder matches(String... regexes) {
      matches = List.of(regexes);
      return this;
    }

    ExpectBuilder notMatches(String... regexes) {
      notMatches = List.of(regexes);
      return this;
    }

    ExpectBuilder endsWithQuestion(boolean value) {
      endsWithQuestion = value;
      return this;
    }

    ExpectBuilder tools(String... names) {
      tools = List.of(names);
      return this;
    }

    ExpectBuilder turnStatus(TurnStatus value) {
      turnStatus = value;
      return this;
    }

    Expect build() {
      return new Expect(
          intent,
          null,
          flags,
          endReason,
          null,
          null,
          null,
          conversationEnded,
          null,
          modelCalled,
          replySent,
          matches,
          notMatches,
          endsWithQuestion,
          tools,
          turnStatus,
          null,
          null,
          null,
          null,
          null,
          null);
    }
  }
}
