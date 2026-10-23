package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enrola.anna.AppClock;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.conversation.Source;
import com.enrola.anna.scheduling.Slot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The seeder against the real database: the rows, the slots, the state and the clock. */
class SeederTest extends EvalFixture {

  @Autowired Seeder seeder;
  @Autowired AppClock clock;

  @Test
  void writesHistoryAsRowsWithTurnNumbers() {
    Scenario.Turn scenario = ScenarioFixtures.load("turn-slot-taken-on-booking");

    Conversation seeded = seeder.seed(scenario);

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select turn_no, direction, text from message where conversation_id = ?"
                + " order by turn_no, direction",
            seeded.id);
    assertThat(rows)
        .extracting(row -> row.get("TURN_NO") + " " + row.get("DIRECTION"))
        .containsExactly("0 OUT", "1 IN", "1 OUT", "2 IN", "2 OUT");
    assertThat(rows.getFirst().get("TEXT")).isEqualTo(scenario.history().getFirst().agent());
    assertThat(seeded.agentTurns).isEqualTo(2);
    assertThat(seeded.source).isEqualTo(Source.EVAL);
  }

  @Test
  void resolvesOfferedLabelsToSlots() {
    Conversation seeded = seeder.seed(ScenarioFixtures.load("turn-slot-taken-on-booking"));

    List<Slot> offer = seeded.lastOfferedSlots();
    assertThat(offer)
        .extracting(Slot::label)
        .containsExactly(
            "Mon 27 Jul 12:00 PM AEST", "Mon 27 Jul 12:30 PM AEST", "Mon 27 Jul 1:00 PM AEST");
    assertThat(offer.getFirst().startUtc()).isEqualTo(Instant.parse("2026-07-27T02:00:00Z"));
    assertThat(offer).extracting(Slot::slotId).doesNotHaveDuplicates();
  }

  @Test
  void appliesTheSeededBookingAndEnd() {
    Conversation seeded = seeder.seed(ScenarioFixtures.load("turn-reschedule-after-booking"));

    assertThat(seeded.goalMet).isTrue();
    assertThat(seeded.stage).isEqualTo(Stage.BOOKED);
    assertThat(seeded.endReason).isEqualTo(EndReason.BOOKED);
    assertThat(
            jdbc.queryForList(
                "select local_label from booking where conversation_id = ?",
                String.class,
                seeded.id))
        .containsExactly("Mon 27 Jul 12:00 PM AEST");
  }

  @Test
  void fixesTheClockAndGivesEachScenarioItsOwnLead() {
    Scenario.Turn scenario = ScenarioFixtures.load("turn-slot-taken-on-booking");

    Conversation seeded = seeder.seed(scenario);

    assertThat(clock.now()).isEqualTo(scenario.clock().toInstant());
    assertThat(seeded.leadId).isEqualTo(scenario.id());
    assertThat(
            jdbc.queryForObject("select phone from lead where id = ?", String.class, scenario.id()))
        .startsWith("+6149")
        .isNotEqualTo(
            jdbc.queryForObject("select phone from lead where id = 'lauren'", String.class));
  }

  /** A repeat seed gets a lead and phone of its own, so the scenario can run twice. */
  @Test
  void aRepeatSeedGetsItsOwnLead() {
    Scenario.Turn scenario = ScenarioFixtures.load("turn-slot-taken-on-booking");

    Conversation first = seeder.seed(scenario);
    Conversation second = seeder.seed(scenario);

    assertThat(List.of(first.leadId, second.leadId))
        .containsExactly(scenario.id(), scenario.id() + "-2");
    assertThat(
            jdbc.queryForList(
                "select distinct phone from lead where id like 'turn-slot-taken-on-booking%'",
                String.class))
        .hasSize(2);
  }

  @Test
  void seedsTheClosingRepliesAlreadySent() {
    Conversation seeded = seeder.seed(ScenarioFixtures.load("turn-silent-after-closing-reply"));

    assertThat(seeded.ended).isTrue();
    assertThat(seeded.postTerminalReplies).isEqualTo(1);
  }

  @Test
  void unresolvableLabelFailsBeforeAnyRowIsWritten() {
    Scenario.Turn taken = ScenarioFixtures.load("turn-slot-taken-on-booking");
    Scenario.Turn sunday =
        new Scenario.Turn(
            "turn-sunday-offer",
            taken.title(),
            taken.tags(),
            taken.lead(),
            taken.clock(),
            taken.calendar(),
            null,
            List.of(
                taken.history().getFirst(),
                new Scenario.History(null, "sunday?", null),
                new Scenario.History("Sunday 9?", null, List.of("Sun 26 Jul 9:00 AM AEST"))),
            "9",
            taken.expect(),
            null,
            null);

    assertThatThrownBy(() -> seeder.seed(sunday))
        .isInstanceOf(ScenarioException.class)
        .hasMessageContaining("Sun 26 Jul 9:00 AM AEST");
    assertThat(jdbc.queryForObject("select count(*) from conversation", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from lead", Integer.class)).isEqualTo(3);
  }
}
