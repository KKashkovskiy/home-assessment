package com.enrola.anna.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.config.AgentProperties;
import com.enrola.anna.config.LimitsProperties;
import com.enrola.anna.config.PersonaProperties;
import com.enrola.anna.config.SmsProperties;
import com.enrola.anna.lead.Lead;
import com.enrola.anna.prompt.PromptBundle;
import com.enrola.anna.prompt.PromptLoader;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

/**
 * The loop over the scripted model, with no network: the request built from the shipped v1 bundle,
 * function calls answered round by round, each round reported as it ends, and the turn's tool
 * rounds as the cap. The fake model only queues responses; the loop under test is the production
 * one.
 */
class AgentServiceTest {

  static final PersonaProperties PERSONA = new PersonaProperties("Anna", "Comparato", true);
  static final SmsProperties SMS = new SmsProperties(320, 300, 1600);
  static final PromptBundle BUNDLE =
      new PromptLoader(new DefaultResourceLoader(), PERSONA, JsonMapper.builder().build(), "v1")
          .active();
  static final List<InputItem> TRANSCRIPT =
      List.of(new InputItem.AssistantText("Hi Lauren"), new InputItem.UserText("save money"));
  static final TurnContext CONTEXT =
      new TurnContext(
          new Lead(
              "lauren",
              "Lauren",
              "+61400000001",
              null,
              "VIC",
              "Bupa",
              null,
              null,
              null,
              Instant.EPOCH),
          Instant.parse("2026-07-26T00:00:00Z").atZone(ZoneId.of("Australia/Melbourne")),
          false,
          new TurnContext.Counters(0, 0, 0, 0),
          new LimitsProperties(15, 3, 1),
          null,
          List.of(),
          false,
          EndReason.NONE,
          null);
  static final String AVAILABILITY = "{\"date_from\":\"2026-07-27\"}";
  static final String BOOKING = "{\"slot_id\":\"sA\"}";
  static final String BOOKED = "{\"status\":\"BOOKED\",\"booking_id\":\"cal-1\"}";

  final ScriptedModelClient model = new ScriptedModelClient();
  final AgentService agent =
      new AgentService(
          new StaticListableBeanFactory(Map.of("model", model)).getBeanProvider(ModelClient.class),
          new AgentProperties("gpt-5.1", "low", "auto", Duration.ofSeconds(30), 2, 1200, 4, false),
          PERSONA,
          SMS);

  @Test
  void transcriptIsReplayedWithTheContextLast() {
    model.decide(Decisions.engaged().build());

    agent.decide(BUNDLE, TRANSCRIPT, CONTEXT, new Rounds(0));

    assertThat(model.calls().getFirst().input())
        .containsExactly(
            new InputItem.AssistantText("Hi Lauren"),
            new InputItem.UserText("save money"),
            new InputItem.UserText(CONTEXT.render()));
  }

  @Test
  void requestCarriesTheRenderedPromptAndTheStrictSchema() {
    model.decide(Decisions.engaged().build());

    agent.decide(BUNDLE, TRANSCRIPT, CONTEXT, new Rounds(0));

    ModelRequest request = model.calls().getFirst();
    assertThat(request.model()).isEqualTo("gpt-5.1");
    assertThat(request.instructions()).isEqualTo(BUNDLE.render(PERSONA, SMS)).doesNotContain("{{");
    assertThat(request.schemaName()).isEqualTo("agent_decision");
    assertThat(request.schema()).isEqualTo(BUNDLE.schema().get("schema"));
    assertThat(request.reasoningEffort()).isEqualTo("low");
  }

  /** The decision call offers the bundle's two tools; after an end nothing could be right. */
  @Test
  void onlyTheDecisionCallOffersTools() {
    model.decide(Decisions.engaged().build()).decide(Decisions.engaged().build());

    agent.decide(BUNDLE, TRANSCRIPT, CONTEXT, new Rounds(0));
    agent.classify(BUNDLE, TRANSCRIPT, CONTEXT, new Rounds(0));

    assertThat(model.calls().get(0).tools())
        .extracting(definition -> definition.get("name").stringValue())
        .containsExactly("get_availability", "book_call");
    assertThat(model.calls().get(1).tools()).isEmpty();
  }

  /**
   * Each round is reported before its call is answered, each answer goes back to the model after
   * the call it answers, and the result sums what the rounds used.
   */
  @Test
  void availabilityThenBookThenDecision() {
    model
        .callTool("get_availability", AVAILABILITY)
        .callTool("book_call", BOOKING)
        .decide(Decisions.engaged().build());
    Rounds rounds = new Rounds(4);

    AgentService.TurnResult result = agent.decide(BUNDLE, TRANSCRIPT, CONTEXT, rounds);

    assertThat(rounds.events)
        .containsExactly(
            "ended ToolCalls",
            "answer get_availability",
            "ended ToolCalls",
            "answer book_call",
            "ended Decided");
    assertThat(model.calls()).hasSize(3);
    assertThat(model.calls().get(2).input())
        .endsWith(
            Decisions.REASONING,
            new InputItem.FunctionCall("call-book_call", "book_call", BOOKING),
            new InputItem.FunctionOutput("call-book_call", BOOKED));
    assertThat(model.calls().get(2).input())
        .startsWith(model.calls().get(1).input().toArray(new InputItem[0]));
    assertThat(result.last()).isInstanceOf(ModelResponse.Decided.class);
    assertThat(result.toolCalls())
        .containsExactly(
            new ToolCall("get_availability", AVAILABILITY, "slots"),
            new ToolCall("book_call", BOOKING, BOOKED));
    assertThat(result.usage()).isEqualTo(new ModelResponse.Usage(3000, 0, 300, 0));
    assertThat(result.latencyMs()).isEqualTo(15);
  }

  /** Two rounds are answered; the third still calls a tool, so the call fails unanswered. */
  @Test
  void stopsAtRoundCap() {
    model
        .callTool("get_availability", AVAILABILITY)
        .callTool("get_availability", AVAILABILITY)
        .callTool("get_availability", AVAILABILITY);
    Rounds rounds = new Rounds(2);

    AgentService.TurnResult result = agent.decide(BUNDLE, TRANSCRIPT, CONTEXT, rounds);

    assertThat(result.last())
        .isInstanceOfSatisfying(
            ModelResponse.Failed.class,
            failed -> assertThat(failed.code()).isEqualTo(ModelResponse.Failure.TOOL_ROUNDS));
    assertThat(rounds.events).filteredOn(event -> event.startsWith("answer")).hasSize(2);
    assertThat(model.calls()).hasSize(3);
    assertThat(result.toolCalls()).hasSize(2);
    assertThat(result.usage()).isEqualTo(new ModelResponse.Usage(3000, 0, 300, 0));
  }

  // The scripted client has no loop and no reference to the tools: the loop above is the only one.

  /**
   * The turn's side: records what happened, grants {@code toolRounds}, answers with canned JSON.
   */
  private static final class Rounds implements ToolLoopCallbacks {

    final List<String> events = new ArrayList<>();
    private int toolRounds;

    Rounds(int toolRounds) {
      this.toolRounds = toolRounds;
    }

    @Override
    public void roundEnded(ModelResponse response) {
      events.add("ended " + response.getClass().getSimpleName());
    }

    @Override
    public boolean grantToolRound() {
      return toolRounds-- > 0;
    }

    @Override
    public String answer(String name, String argumentsJson) {
      events.add("answer " + name);
      return name.equals("book_call") ? BOOKED : "slots";
    }
  }
}
