package com.enrola.anna.eval;

import com.enrola.anna.agent.PriceTable;
import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.config.SettingsStore;
import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptVersion;
import com.enrola.anna.prompt.PromptVersionRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Prepares a run of the suite on this context from its options, the same way for the CLI and the
 * offline test: the app's active prompt version, the cap, the selection, the judges and their
 * rubrics, the header, the budget, and the runners of both kinds with the judge.
 */
final class EvalRuns {

  private final TurnRunner turns;
  private final StoryRunner stories;
  private final TurnRowsReader reader;
  private final EvalModels models;
  private final ObjectProvider<JudgeClient> judge;
  private final SettingsStore settings;
  private final PromptStore prompts;
  private final PromptVersionRepository versions;

  EvalRuns(
      TurnRunner turns,
      StoryRunner stories,
      TurnRowsReader reader,
      EvalModels models,
      ObjectProvider<JudgeClient> judge,
      SettingsStore settings,
      PromptStore prompts,
      PromptVersionRepository versions) {
    this.turns = turns;
    this.stories = stories;
    this.reader = reader;
    this.models = models;
    this.judge = judge;
    this.settings = settings;
    this.prompts = prompts;
    this.versions = versions;
  }

  EvalRun prepare(EvalOptions options) throws IOException {
    AppState.activePrompt(options.appDbUrl()).ifPresent(this::activate);
    BigDecimal cap =
        options.budgetUsd() == null ? settings.current().budgets().evalCap() : options.budgetUsd();
    raiseAppCap(cap);
    RunHeader header = header(options, cap);
    var plan =
        new RunPlan(
            header,
            options.selection().apply(new ScenarioLoader().loadAll(ScenarioLoader.SCENARIOS)),
            header.judges().judgeModel() == null
                ? List.of()
                : RubricLoader.load(RubricLoader.RUBRICS),
            options.dir(),
            options.appDbUrl());
    var budget =
        new EvalBudget(cap, new PriceTable(settings.current().priceTable()), header.model());
    return new EvalRun(
        new EvalRun.Steps(
            turns::run,
            (story, trialNo) -> stories.run(story, trialNo, budget),
            (scenario, trial, question) ->
                judge
                    .getObject()
                    .ask(question.rubric(), reader.transcript(scenario, trial), question.turnNo()),
            (scenario, trial, turnNo) -> !reader.transcript(scenario, trial).modelReplied(turnNo)),
        budget,
        plan);
  }

  private RunHeader header(EvalOptions options, BigDecimal cap) {
    Settings.Agent agent = settings.current().agent();
    return new RunHeader(
        options.runId(),
        prompts.activeId(),
        agent.model(),
        agent.reasoning().value(),
        JudgeMode.of(options.judges(), options.mode(), models.judge()),
        options.mode(),
        options.selection().describe(),
        options.trials(),
        options.validityThreshold(),
        cap,
        Instant.now());
  }

  /** The app's version, added to this run's database when the repo did not seed it. */
  private void activate(PromptVersion appVersion) {
    if (!versions.existsById(appVersion.id())) {
      versions.save(appVersion);
    }
    prompts.activate(appVersion.id());
  }

  /**
   * The app's spend cap in a run sums the run's own turns, so it must not stop a call the run's cap
   * still allows; the run's budget skips trials first.
   */
  private void raiseAppCap(BigDecimal cap) {
    Settings current = settings.current();
    if (current.budgets().appCap().compareTo(cap) < 0) {
      settings.save(SettingsFixture.with(current, "budgets", "appCap", cap));
    }
  }
}
