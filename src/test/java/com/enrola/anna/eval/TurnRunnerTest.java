package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.lead.Lead;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** What the runner makes of a turn the product answers, of a repeat, and of a refused start. */
class TurnRunnerTest extends EvalFixture {

  @Autowired TurnRunner runner;

  @Test
  void stopIsAnsweredWithoutTheModel() {
    Trial trial = runner.run(ScenarioFixtures.load("turn-stop-keyword"), 1);

    assertThat(trial.status()).isEqualTo(TrialStatus.PASS);
    assertThat(trial.modelCalls()).isZero();
    assertThat(trial.checks()).extracting(Check::name).contains("model_called", "turn_status");
  }

  /** Each run of a scenario has a lead of its own, so a STOP in the first suppresses nobody. */
  @Test
  void aScenarioRunsTwiceInOneDatabase() {
    Scenario.Turn stop = ScenarioFixtures.load("turn-stop-keyword");

    assertThat(runner.run(stop, 1).status()).isEqualTo(TrialStatus.PASS);
    assertThat(runner.run(stop, 2).status()).isEqualTo(TrialStatus.PASS);
  }

  /** Two inline leads on one phone: the first STOPs, so the product refuses the second's start. */
  @Test
  void aStartTheProductRefusesIsAFailWithTheRefusalAsEvidence() {
    Scenario.Turn stop = ScenarioFixtures.load("turn-stop-keyword");
    var kim =
        new Scenario.LeadRef(
            null,
            new Lead(
                null, "Kim", "+61491570111", null, "VIC", null, null, null, null, Instant.EPOCH));
    runner.run(withLead(stop, "turn-kim-stops", kim), 1);

    Trial refused = runner.run(withLead(stop, "turn-kim-again", kim), 1);

    assertThat(refused.status()).isEqualTo(TrialStatus.FAIL);
    assertThat(refused.checks())
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.name()).isEqualTo("conversation");
              assertThat(check.evidence()).startsWith("SUPPRESSED");
            });
  }

  /** The cap override holds for the trial alone. */
  @Test
  void maxTurnsOverrideIsRestored() {
    int before = settings.current().limits().maxTurns();

    Trial trial = runner.run(ScenarioFixtures.load("turn-max-turns"), 1);

    assertThat(trial.status()).isEqualTo(TrialStatus.PASS);
    assertThat(settings.current().limits().maxTurns()).isEqualTo(before);
  }

  private static Scenario.Turn withLead(Scenario.Turn turn, String id, Scenario.LeadRef lead) {
    return new Scenario.Turn(
        id,
        turn.title(),
        turn.tags(),
        lead,
        turn.clock(),
        turn.calendar(),
        turn.seed(),
        turn.history(),
        turn.message(),
        turn.expect(),
        turn.judge(),
        turn.config());
  }
}
