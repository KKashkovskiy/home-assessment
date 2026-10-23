package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enrola.anna.agent.Decisions;
import com.enrola.anna.agent.InputItem;
import com.enrola.anna.agent.ModelRequest;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.PriceTable;
import com.enrola.anna.agent.ScriptedModelClient;
import com.enrola.anna.config.Settings.CalendarPreset;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.conversation.Message;
import com.enrola.anna.conversation.MessageSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The actor over a scripted model: the line comes back, and each kind of persona break is caught.
 */
class LeadSimulatorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<Message> TRANSCRIPT =
      List.of(
          Message.outbound("c-1", 0, "Hi John, it's Anna.", MessageSource.TEMPLATE, Instant.EPOCH),
          Message.inbound("c-1", 1, "who is this", "p-1", Instant.EPOCH));

  final ScriptedModelClient actor = new ScriptedModelClient();
  final LeadSimulator simulator =
      new LeadSimulator(
          actor,
          "gpt-5.1",
          new PriceTable(SettingsFixture.DEFAULTS.priceTable()),
          new LeadSimulator.Rules("RULES", "CLOSING"));

  @Test
  void returnsLine() {
    says("too busy for this mate");

    Simulator.Next next =
        simulator.next(persona("You are John."), TRANSCRIPT, Simulator.Moment.TALKING);

    assertThat(next)
        .isInstanceOfSatisfying(
            Simulator.Line.class,
            line -> {
              assertThat(line.text()).isEqualTo("too busy for this mate");
              assertThat(line.costUsd()).isPositive();
            });
    ModelRequest request = actor.calls().getFirst();
    assertThat(request.tools()).isEmpty();
    assertThat(request.schemaName()).isEqualTo("lead_line");
    assertThat(((InputItem.UserText) request.input().getFirst()).text())
        .contains("<persona>\nYou are John.\n</persona>")
        .contains("Anna: Hi John, it's Anna.\nYou: who is this");
  }

  @Test
  void aiClaimIsPersonaBreak() {
    says("As an AI I can't answer that");

    assertThat(simulator.next(persona("You are John."), TRANSCRIPT, Simulator.Moment.TALKING))
        .isInstanceOfSatisfying(
            Simulator.PersonaBreak.class,
            broken -> assertThat(broken.reason()).startsWith("actor claimed to be an AI"));
  }

  /** A persona that allows STOP lets it through: Anna must then unsubscribe. */
  @Test
  void forbiddenStopIsPersonaBreak() {
    says("STOP");
    says("STOP");

    assertThat(
            simulator.next(
                persona("You are John. Never write STOP."), TRANSCRIPT, Simulator.Moment.TALKING))
        .isInstanceOfSatisfying(
            Simulator.PersonaBreak.class,
            broken -> assertThat(broken.reason()).startsWith("persona forbids"));
    assertThat(simulator.next(persona("You are John."), TRANSCRIPT, Simulator.Moment.TALKING))
        .isInstanceOf(Simulator.Line.class);
  }

  /** Directed profanity from a persona that never swears is the actor's error, not Anna's. */
  @Test
  void swearingAgainstThePersonaIsPersonaBreak() {
    says("fuck off with this");

    assertThat(
            simulator.next(
                persona("You are John. Never swear at the agent."),
                TRANSCRIPT,
                Simulator.Moment.TALKING))
        .isInstanceOfSatisfying(
            Simulator.PersonaBreak.class,
            broken -> assertThat(broken.reason()).startsWith("persona forbids"));
  }

  /** An unpriced actor would spend past the cap unseen, so it is refused before any call. */
  @Test
  void anUnpricedModelIsRefused() {
    assertThatThrownBy(
            () ->
                new LeadSimulator(
                    actor,
                    "gpt-5-mini",
                    new PriceTable(SettingsFixture.DEFAULTS.priceTable()),
                    new LeadSimulator.Rules("RULES", "CLOSING")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("the simulated lead's model gpt-5-mini has no price");
  }

  @Test
  void emptyIsPersonaBreak() {
    says("   ");

    assertThat(simulator.next(persona("You are John."), TRANSCRIPT, Simulator.Moment.TALKING))
        .isEqualTo(new Simulator.PersonaBreak("empty line", cost()));
  }

  @Test
  void aFailedCallIsPersonaBreak() {
    actor.enqueue(Decisions.failed(ModelResponse.Failure.HTTP_5XX));

    assertThat(simulator.next(persona("You are John."), TRANSCRIPT, Simulator.Moment.TALKING))
        .isInstanceOfSatisfying(
            Simulator.PersonaBreak.class,
            broken ->
                assertThat(broken.reason()).isEqualTo("actor call failed: HTTP_5XX scripted"));
  }

  @Test
  void closingRulesAppendedAfterEnd() {
    says("cheers");
    says("cheers");

    simulator.next(persona("You are John."), TRANSCRIPT, Simulator.Moment.TALKING);
    simulator.next(persona("You are John."), TRANSCRIPT, Simulator.Moment.AFTER_END);

    assertThat(actor.calls())
        .extracting(ModelRequest::instructions)
        .containsExactly("RULES", "RULES\n\nCLOSING");
  }

  private void says(String text) {
    actor.enqueue(
        new ModelResponse.Decided(
            JSON.createObjectNode().put("text", text), null, Decisions.USAGE, 5, "{}", "{}"));
  }

  /** What one scripted call costs at gpt-5.1's price. */
  private static BigDecimal cost() {
    return new PriceTable(SettingsFixture.DEFAULTS.priceTable()).cost("gpt-5.1", Decisions.USAGE);
  }

  private static Persona persona(String text) {
    return Persona.of(story(text));
  }

  private static Scenario.Story story(String persona) {
    return new Scenario.Story(
        "story-x",
        "Title of story-x",
        List.of(),
        new Scenario.LeadRef("john", null),
        ScenarioFixtures.SUNDAY,
        CalendarPreset.NORMAL,
        persona,
        List.of("6 turns"),
        Scenario.Expect.NONE,
        null,
        null);
  }
}
