package com.enrola.anna.eval;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.config.SettingsStore;
import com.enrola.anna.conversation.ConflictException;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.conversation.InboundTurns;
import com.enrola.anna.conversation.TraceEventType;
import com.enrola.anna.conversation.Turn;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Runs one turn scenario: seed the rows, send the lead message through {@link InboundTurns}, the
 * path the API takes, so idempotency, locks, pre-guards, model, post-guards and persistence are all
 * under test, then read the rows back and check them. Never calls the agent directly. The offline
 * stand-ins, when the context has them, are armed with the scenario first.
 */
final class TurnRunner {

  private final Seeder seeder;
  private final InboundTurns inbound;
  private final TurnRowsReader reader;
  private final SettingsStore settings;
  private final ObjectProvider<StandInModel> standIn;
  private final ObjectProvider<ScenarioModeration> moderation;

  TurnRunner(
      Seeder seeder,
      InboundTurns inbound,
      TurnRowsReader reader,
      SettingsStore settings,
      ObjectProvider<StandInModel> standIn,
      ObjectProvider<ScenarioModeration> moderation) {
    this.seeder = seeder;
    this.inbound = inbound;
    this.reader = reader;
    this.settings = settings;
    this.standIn = standIn;
    this.moderation = moderation;
  }

  /** The trial; a scenario's {@code config.max_turns} holds for it alone. */
  Trial run(Scenario.Turn scenario) {
    Integer maxTurns = scenario.config().maxTurns();
    if (maxTurns == null) {
      return trial(scenario);
    }
    Settings before = settings.current();
    settings.save(SettingsFixture.with(before, "limits", "maxTurns", maxTurns));
    try {
      return trial(scenario);
    } finally {
      settings.save(before);
    }
  }

  /**
   * A scenario the harness cannot run, including a reply the stand-in cannot write, is INVALID; a
   * conversation the product refuses to start is a FAIL with the refusal as evidence.
   */
  private Trial trial(Scenario.Turn scenario) {
    long started = System.nanoTime();
    try {
      Conversation seeded = seeder.seed(scenario);
      standIn.ifAvailable(model -> model.arm(scenario, seeded));
      moderation.ifAvailable(screen -> screen.arm(scenario));
      inbound.handle(seeded.id, scenario.message(), "eval-" + scenario.id());
      return checked(
          scenario,
          reader.latestTurn(seeded.id),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    } catch (ScenarioException e) {
      return Trial.invalid(scenario, e.getMessage());
    } catch (ConflictException e) {
      return Trial.refused(
          scenario, Check.expectation("conversation", false, e.code() + ": " + e.getMessage()));
    }
  }

  private static Trial checked(Scenario.Turn scenario, TurnRows rows, long durationMs) {
    List<Check> checks =
        Stream.concat(
                Checks.forTurn(scenario.expect(), rows).stream(),
                Checks.suiteWide(scenario.expect(), rows).stream())
            .toList();
    Turn turn = rows.turn();
    return new Trial(
        scenario.id(),
        scenario.kind(),
        checks.stream().allMatch(Check::pass) ? TrialStatus.PASS : TrialStatus.FAIL,
        checks,
        List.of(),
        turn == null ? BigDecimal.ZERO : turn.modelCall().costUsd(),
        rows.eventsOf(TraceEventType.MODEL_CALL).size(),
        rows.conversation().id,
        rows.turnNo(),
        rows.reply(),
        rows.eventsOf(TraceEventType.TOOL_CALL).stream()
            .map(event -> event.name() + " " + event.payloadJson())
            .toList(),
        durationMs);
  }
}
