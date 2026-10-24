package com.enrola.anna.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.Decisions;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.ScriptedModelClient;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.guard.ModerationVerdict;
import com.enrola.anna.guard.ScriptedModerationClient;
import com.enrola.anna.lead.LeadRepository;
import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptVersion;
import com.enrola.anna.prompt.PromptVersionRepository;
import com.enrola.anna.prompt.Template;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A conversation keeps the prompt version it started on, whatever is activated later. A turn on a
 * version that cannot load never calls the model on another version: it hands off, while STOP and
 * the safety reply still go out with the active version's templates.
 */
@SpringBootTest(properties = "OPENAI_API_KEY=")
@Import(PinnedVersionTest.ScriptedModel.class)
class PinnedVersionTest {

  /** A line only the second version's prompt has. */
  static final String MARKER = "PIN-TEST-MARKER";

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void dataInTempDir(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("anna"));
    registry.add("anna.data-dir", tempDir::toString);
  }

  @TestConfiguration
  static class ScriptedModel {
    @Bean
    ScriptedModelClient scriptedModel() {
      return new ScriptedModelClient();
    }

    @Bean
    ScriptedModerationClient scriptedModeration() {
      return new ScriptedModerationClient();
    }
  }

  @Autowired ConversationService conversations;
  @Autowired InboundTurns turns;
  @Autowired ScriptedModelClient model;
  @Autowired ScriptedModerationClient moderation;
  @Autowired LeadRepository leads;
  @Autowired PromptStore prompts;
  @Autowired PromptVersionRepository versions;
  @Autowired JdbcTemplate jdbc;
  String laurenOnV1;

  @BeforeEach
  void laurenStartsOnV1() {
    jdbc.update("delete from trace_event");
    jdbc.update("delete from turn");
    jdbc.update("delete from message");
    jdbc.update("delete from conversation");
    jdbc.update("delete from suppression");
    jdbc.update("delete from prompt_version where id <> 'v1'");
    versions.activate("v1");
    model.reset();
    moderation.reset();
    laurenOnV1 = conversations.start("lauren", Source.API).conversationId();
  }

  @Test
  void turnUsesPinnedNotActive() {
    prompts.activate(saveV1WithMarker());
    model.decide(Decisions.engaged().build());

    turns.handle(laurenOnV1, "save money", "p1");

    assertThat(model.calls().getLast().instructions()).doesNotContain(MARKER);
    assertThat(turnPromptVersion(laurenOnV1)).isEqualTo("v1");
  }

  @Test
  void newConversationUsesActive() {
    prompts.activate(saveV1WithMarker());

    TurnResponse started = conversations.start("john", Source.API);
    model.decide(Decisions.engaged().build());
    turns.handle(started.conversationId(), "save money", "j1");

    assertThat(started.promptVersion()).isEqualTo("v2");
    assertThat(model.calls().getLast().instructions()).contains(MARKER);
    assertThat(turnPromptVersion(started.conversationId())).isEqualTo("v2");
  }

  @Test
  void missingPinnedVersionHandsOff() {
    pinLaurenTo("v7");

    TurnResponse response = turns.handle(laurenOnV1, "hello", "p1");

    assertHandedOffWithoutPrompt(response, "prompt version v7 does not exist");
  }

  @Test
  void stopOnAMissingVersionStillOptsOut() {
    pinLaurenTo("v7");

    TurnResponse response = turns.handle(laurenOnV1, "STOP", "p1");

    assertThat(response.unsubscribed()).isTrue();
    assertThat(response.reply()).isEqualTo(activeTemplate(Template.UNSUBSCRIBE_CONFIRMATION));
    assertThat(
            jdbc.queryForObject(
                "select reason from suppression where phone = '+61400000001'", String.class))
        .isEqualTo("KEYWORD");
    assertThat(model.calls()).isEmpty();
  }

  @Test
  void selfHarmOnAMissingVersionStillGetsTheSafetyReply() {
    pinLaurenTo("v7");
    moderation.enqueue(new ModerationVerdict(0, 0, 0.9, true));

    TurnResponse response = turns.handle(laurenOnV1, "i want to end it", "p1");

    assertThat(response.reply()).isEqualTo(activeTemplate(Template.SELF_HARM_SAFETY));
    assertThat(model.calls()).isEmpty();
  }

  @Test
  void corruptPinnedVersionHandsOff() {
    String v2 = saveV1WithMarker();
    jdbc.update("update prompt_version set tools_json = '{' where id = ?", v2);
    pinLaurenTo(v2);

    TurnResponse response = turns.handle(laurenOnV1, "hello", "p1");

    assertHandedOffWithoutPrompt(
        response, "prompt version v2 cannot be loaded: tools are not valid JSON");
    assertThat(prompts.activeId()).isEqualTo("v1");
  }

  /**
   * Nothing sent, no model call, a human asked to look, the conversation open, and the trace says
   * which version failed and that the pinned-prompt step kept the model out.
   */
  private void assertHandedOffWithoutPrompt(TurnResponse response, String detail) {
    assertThat(response.reply()).isNull();
    assertThat(response.needsHuman()).isTrue();
    assertThat(response.handoffReason()).isEqualTo(HandoffReason.ERROR);
    assertThat(response.conversationEnded()).isFalse();
    assertThat(model.calls()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select payload_json from trace_event"
                    + " where conversation_id = ? and name = 'pinned-prompt'",
                String.class,
                laurenOnV1))
        .contains("\"reason\":\"PROMPT\"");
    Map<String, Object> error =
        jdbc.queryForMap(
            "select name, outcome, payload_json from trace_event"
                + " where conversation_id = ? and type = 'ERROR'",
            laurenOnV1);
    assertThat(error).containsEntry("NAME", "prompt-load").containsEntry("OUTCOME", "BLOCK");
    assertThat(error.get("PAYLOAD_JSON").toString())
        .contains(
            "\"code\":\"PROMPT\"",
            "\"detail\":\"" + detail + "; fixed templates from " + prompts.activeId() + "\"");
  }

  /** v1 with one line added to the prompt, saved as the next version; returns its id. */
  private String saveV1WithMarker() {
    PromptVersion v1 = versions.findById("v1").orElseThrow();
    PromptStore.SaveResult result =
        prompts.save(
            new PromptStore.SaveRequest(
                v1.promptMd() + "\n" + MARKER + "\n",
                v1.knowledgeMd(),
                v1.schemaJson(),
                v1.toolsJson(),
                v1.templatesJson(),
                "pin test"));
    return ((PromptStore.SaveResult.Saved) result).id();
  }

  private void pinLaurenTo(String version) {
    jdbc.update("update conversation set prompt_version = ? where id = ?", version, laurenOnV1);
  }

  private String activeTemplate(Template template) {
    return prompts
        .bundle(prompts.activeId(), SettingsFixture.DEFAULTS.persona())
        .templates()
        .render(template, leads.findById("lauren").orElseThrow());
  }

  /** The version whose text the model was sent, as the turn row records it. */
  private String turnPromptVersion(String conversationId) {
    return jdbc.queryForObject(
        "select prompt_version from turn where conversation_id = ?", String.class, conversationId);
  }
}
