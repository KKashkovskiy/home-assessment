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

/**
 * Prepares a run of the suite on this context from its options, the same way for the CLI and the
 * offline test: the app's active prompt version, the cap, the selection, the header, the budget and
 * the runners of both kinds.
 */
final class EvalRuns {

  private final TurnRunner turns;
  private final StoryRunner stories;
  private final SettingsStore settings;
  private final PromptStore prompts;
  private final PromptVersionRepository versions;

  EvalRuns(
      TurnRunner turns,
      StoryRunner stories,
      SettingsStore settings,
      PromptStore prompts,
      PromptVersionRepository versions) {
    this.turns = turns;
    this.stories = stories;
    this.settings = settings;
    this.prompts = prompts;
    this.versions = versions;
  }

  EvalRun prepare(EvalOptions options) throws IOException {
    AppState.activePrompt(options.appDbUrl()).ifPresent(this::activate);
    BigDecimal cap =
        options.budgetUsd() == null ? settings.current().budgets().evalCap() : options.budgetUsd();
    raiseAppCap(cap);
    Settings.Agent agent = settings.current().agent();
    var header =
        new RunHeader(
            options.runId(),
            prompts.activeId(),
            agent.model(),
            agent.reasoning().value(),
            options.mode(),
            options.selection().describe(),
            options.trials(),
            options.validityThreshold(),
            cap,
            Instant.now());
    var plan =
        new RunPlan(
            header,
            options.selection().apply(new ScenarioLoader().loadAll(ScenarioLoader.SCENARIOS)),
            options.dir(),
            options.appDbUrl());
    var budget =
        new EvalBudget(cap, new PriceTable(settings.current().priceTable()), agent.model());
    return new EvalRun(
        turns::run, (story, trialNo) -> stories.run(story, trialNo, budget), budget, plan);
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
