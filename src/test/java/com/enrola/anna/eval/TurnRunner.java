package com.enrola.anna.eval;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.config.SettingsStore;
import com.enrola.anna.conversation.ConflictException;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.conversation.InboundTurns;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Runs one turn scenario: seed the rows, send the lead message through {@link InboundTurns}, the
 * path the API takes, so idempotency, locks, pre-guards, model, post-guards and persistence are all
 * under test, then read the rows back and check them. Never calls the agent directly. Offline, the
 * model is armed with a {@link StandInModel} for the scenario and the moderation with its scores.
 */
final class TurnRunner {

  private final Seeder seeder;
  private final InboundTurns inbound;
  private final TurnRowsReader reader;
  private final SettingsStore settings;
  private final ObjectProvider<OfflineModel> offline;
  private final ObjectProvider<ScenarioModeration> moderation;

  TurnRunner(
      Seeder seeder,
      InboundTurns inbound,
      TurnRowsReader reader,
      SettingsStore settings,
      ObjectProvider<OfflineModel> offline,
      ObjectProvider<ScenarioModeration> moderation) {
    this.seeder = seeder;
    this.inbound = inbound;
    this.reader = reader;
    this.settings = settings;
    this.offline = offline;
    this.moderation = moderation;
  }

  /** Trial {@code trialNo}; a scenario's {@code config.max_turns} holds for it alone. */
  Trial run(Scenario.Turn scenario, int trialNo) {
    Integer maxTurns = scenario.config().maxTurns();
    if (maxTurns == null) {
      return trial(scenario, trialNo);
    }
    Settings before = settings.current();
    settings.save(SettingsFixture.with(before, "limits", "maxTurns", maxTurns));
    try {
      return trial(scenario, trialNo);
    } finally {
      settings.save(before);
    }
  }

  /**
   * A scenario the harness cannot run, including a reply the stand-in cannot write, fails on the
   * hard gate; a conversation the product refuses to start is a FAIL with the refusal as evidence.
   */
  private Trial trial(Scenario.Turn scenario, int trialNo) {
    long started = System.nanoTime();
    try {
      Conversation seeded = seeder.seed(scenario);
      offline.ifAvailable(model -> model.arm(new StandInModel(scenario, seeded)));
      moderation.ifAvailable(screen -> screen.arm(scenario));
      inbound.handle(seeded.id, scenario.message(), "eval-" + scenario.id());
      return checked(
          scenario,
          trialNo,
          reader.latestTurn(seeded.id),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    } catch (ScenarioException e) {
      return Trial.scenarioFault(scenario, trialNo, e.getMessage());
    } catch (ConflictException e) {
      return Trial.refused(scenario, trialNo, refusal(e));
    }
  }

  /** A start the product refused, as the check that failed. */
  static Check refusal(ConflictException refused) {
    return Check.expectation("conversation", false, refused.code() + ": " + refused.getMessage());
  }

  private static Trial checked(
      Scenario.Turn scenario, int trialNo, TurnRows rows, long durationMs) {
    List<Check> checks =
        Stream.concat(
                Checks.forTurn(scenario.expect(), rows).stream(),
                Checks.suiteWide(scenario.expect(), rows).stream())
            .toList();
    return new Trial(
        scenario.id(),
        trialNo,
        checks.stream().allMatch(Check::pass) ? TrialStatus.PASS : TrialStatus.FAIL,
        checks,
        List.of(),
        rows.costUsd(),
        rows.modelCalls(),
        rows.conversation().id,
        new Trial.TurnDetail(rows.turnNo(), rows.reply(), rows.toolCalls()),
        durationMs);
  }
}
