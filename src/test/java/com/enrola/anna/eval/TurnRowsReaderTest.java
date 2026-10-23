package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.conversation.Message;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** What a judge would read about a trial the stand-in answered: its lead and every SMS. */
class TurnRowsReaderTest extends EvalFixture {

  @Autowired TurnRunner runner;
  @Autowired TurnRowsReader reader;

  /** The seeded history, the message under test and the reply, in order, the reply marked. */
  @Test
  void transcriptIsTheTrialsWholeConversation() {
    Scenario.Turn scenario = ScenarioFixtures.load("turn-happy-pick-a-time");
    Trial trial = runner.run(scenario, 1);

    TrialTranscript transcript = reader.transcript(scenario, trial);

    assertThat(transcript.lead().givenName()).isEqualTo("Lauren");
    assertThat(transcript.messages())
        .extracting(Message::text)
        .startsWith(scenario.history().getFirst().agent())
        .endsWith(scenario.message(), ((Trial.TurnDetail) trial.detail()).reply());
    assertThat(transcript.render(trial.lastTurn()))
        .endsWith(
            "Lead: "
                + scenario.message()
                + "\n[turn "
                + trial.lastTurn()
                + "] Anna: [UNDER JUDGEMENT] "
                + ((Trial.TurnDetail) trial.detail()).reply());
  }
}
