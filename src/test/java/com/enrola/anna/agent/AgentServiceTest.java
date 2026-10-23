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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

/** The request one decision is asked with, built from the shipped v1 bundle, with no network. */
class AgentServiceTest {

  static final PersonaProperties PERSONA = new PersonaProperties("Anna", "Comparato", true);
  static final SmsProperties SMS = new SmsProperties(320, 300, 1600);
  static final PromptBundle BUNDLE =
      new PromptLoader(new DefaultResourceLoader(), PERSONA, JsonMapper.builder().build(), "v1")
          .active();
  static final List<ModelRequest.InputItem> TRANSCRIPT =
      List.of(new ModelRequest.AssistantText("Hi Lauren"), new ModelRequest.UserText("save money"));
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
          new TurnContext.Counters(0, 0, 0, 0),
          new LimitsProperties(15, 3, 1),
          null,
          List.of(),
          false,
          EndReason.NONE);

  final ScriptedModelClient model = new ScriptedModelClient();
  final AgentService agent =
      new AgentService(
          new StaticListableBeanFactory(Map.of("model", model)).getBeanProvider(ModelClient.class),
          new AgentProperties("gpt-5.1", "low", "auto", Duration.ofSeconds(30), 2, 1200, false),
          PERSONA,
          SMS);

  @Test
  void transcriptIsReplayedWithTheContextLast() {
    model.decide(Decisions.engaged().build());

    agent.decide(BUNDLE, TRANSCRIPT, CONTEXT);

    assertThat(model.calls().getFirst().input())
        .containsExactly(
            new ModelRequest.AssistantText("Hi Lauren"),
            new ModelRequest.UserText("save money"),
            new ModelRequest.UserText(CONTEXT.render()));
  }

  @Test
  void requestCarriesTheRenderedPromptAndTheStrictSchema() {
    model.decide(Decisions.engaged().build());

    agent.decide(BUNDLE, TRANSCRIPT, CONTEXT);

    ModelRequest request = model.calls().getFirst();
    assertThat(request.model()).isEqualTo("gpt-5.1");
    assertThat(request.instructions()).isEqualTo(BUNDLE.render(PERSONA, SMS)).doesNotContain("{{");
    assertThat(request.schemaName()).isEqualTo("agent_decision");
    assertThat(request.schema()).isEqualTo(BUNDLE.schema().get("schema"));
    assertThat(request.reasoningEffort()).isEqualTo("low");
  }
}
