package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enrola.anna.agent.Decisions;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.ScriptedModelClient;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.conversation.InboundTurns;
import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptVersion;
import com.enrola.anna.prompt.PromptVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;

/**
 * The inspector over MockMvc: labelled fields with the raw JSON behind a toggle the session keeps,
 * the stored JSON masked like the page, the prompt as the turn sent it, and the pane on the page.
 */
@Import(ConsoleFixture.Scripted.class)
class InspectorControllerTest extends ConsoleFixture {

  static final String QUESTION = "Got it. Is that hospital, extras or both?";
  static final String HIDDEN_RAW = "<pre class=\"raw-json raw mono\" hidden=\"hidden\">";
  static final String SHOWN_RAW = "<pre class=\"raw-json raw mono\">";
  static final String MARKER = "ONLY-IN-THE-NEWER-VERSION";
  static final String PHONE = "0499 876 543";

  @Autowired ScriptedModelClient model;
  @Autowired InboundTurns inbound;
  @Autowired PromptStore prompts;
  @Autowired PromptVersionRepository versions;

  String id;

  @BeforeEach
  void laurenOnV1AnsweredOnce() {
    model.reset();
    id = start("lauren");
    model.decide(Decisions.engaged().reply(QUESTION).build());
    inbound.handle(id, "my number is " + PHONE, null);
  }

  @Test
  void turnCardShowsLabelledFieldsWithTheJsonHidden() throws Exception {
    String card = body(mvc.perform(get(turnPath(1))).andExpect(status().isOk()));

    assertThat(card)
        .startsWith("<details class=\"turn\" id=\"turn-1\" open=\"open\">")
        .contains("<dt>Intent</dt><dd>ENGAGED</dd>", "<dt>Stage</dt><dd>Engage</dd>", HIDDEN_RAW);
    assertThat(card).containsOnlyOnce("&quot;lead_intent&quot;");
    assertThat(card.indexOf("&quot;lead_intent&quot;")).isGreaterThan(card.indexOf(HIDDEN_RAW));
  }

  @Test
  void rawJsonToggleLastsForTheSession() throws Exception {
    MockHttpSession session = new MockHttpSession();

    mvc.perform(
            post("/ui/raw-json")
                .param("on", "true")
                .header("X-Requested-With", "fetch")
                .session(session))
        .andExpect(status().isNoContent());
    assertThat(body(mvc.perform(get(turnPath(1)).session(session)))).contains(SHOWN_RAW);
    assertThat(body(mvc.perform(get("/conversations/" + id).session(session))))
        .contains(
            "id=\"raw-toggle\" class=\"btn sm needs-turns\" type=\"submit\" aria-pressed=\"true\"");

    mvc.perform(post("/ui/raw-json").param("on", "false").session(session))
        .andExpect(status().is3xxRedirection());
    assertThat(body(mvc.perform(get(turnPath(1)).session(session)))).contains(HIDDEN_RAW);
  }

  @Test
  void rawEndpointServesTheStoredJsonMaskedLikeThePage() throws Exception {
    String masked =
        body(
            mvc.perform(get(turnPath(1) + "/raw"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.decision.lead_intent").value("ENGAGED"))
                .andExpect(jsonPath("$.request.store").value(false))
                .andExpect(jsonPath("$.request.input[1].role").value("user")));
    String clear =
        body(mvc.perform(get(turnPath(1) + "/raw").sessionAttr(PiiMask.SESSION_KEY, false)));

    assertThat(masked).doesNotContain(PHONE);
    assertThat(clear).contains(PHONE);
  }

  @Test
  void promptIsWhatTheTurnSentEvenAfterANewerVersionIsActive() throws Exception {
    prompts.activate(saveV1WithMarker(), SettingsFixture.DEFAULTS.persona());

    String sent =
        body(
            mvc.perform(get("/conversations/" + id + "/prompt?turn=1")).andExpect(status().isOk()));
    String pinned = body(mvc.perform(get("/conversations/" + id + "/prompt")));

    assertThat(sent)
        .contains("Prompt v1 as sent on turn 1", "Now: Sunday 26 July 2026", "given_name: Lauren")
        .doesNotContain(MARKER, PHONE);
    assertThat(pinned).contains("Prompt v1, as pinned", "{{persona}}").doesNotContain(MARKER);
    assertThat(sent).contains("href=\"/configuration?version=v1\"");
  }

  @Test
  void unfinishedTurnUnknownConversationAndBadIdAre404() throws Exception {
    mvc.perform(get(turnPath(2))).andExpect(status().isNotFound());
    mvc.perform(get(turnPath(2) + "/raw")).andExpect(status().isNotFound());
    mvc.perform(get("/conversations/c-nope/turns/1")).andExpect(status().isNotFound());
    mvc.perform(get("/conversations/c-nope/prompt")).andExpect(status().isNotFound());
    mvc.perform(get("/conversations/no%20id/turns/1")).andExpect(status().isNotFound());
  }

  @Test
  void toolCardShowsArgumentsFilteringAndTheSlotTable() throws Exception {
    model
        .callTool(
            "get_availability",
            "{\"date_from\":\"2026-07-27\",\"date_to\":\"2026-07-27\",\"part_of_day\":\"MIDDAY\"}")
        .decide(Decisions.engaged().reply(QUESTION).build());
    inbound.handle(id, "monday lunchtime?", null);

    String card = body(mvc.perform(get(turnPath(2))).andExpect(status().isOk()));

    assertThat(card)
        .contains(
            "<dd class=\"mono lines\">date_from = 2026-07-27\ndate_to = 2026-07-27\npart_of_day = MIDDAY</dd>",
            "<dt>Filtering</dt><dd>22 free in the window; 16 outside the part of day,",
            "<table class=\"slot-table\">",
            "<td>Mon 27 Jul 12:00 PM AEST</td>",
            "⚙ tool calls",
            "✓ answered");
  }

  @Test
  void pageOpensOnlyTheLatestTurnAndTotalsTheCalls() throws Exception {
    model.enqueue(Decisions.failed(ModelResponse.Failure.TIMEOUT));
    inbound.handle(id, "hello?", null);

    String page = body(mvc.perform(get("/conversations/" + id)).andExpect(status().isOk()));

    assertThat(page)
        .contains(
            "<div id=\"turns\" aria-live=\"polite\"",
            "<details class=\"turn\" id=\"turn-1\">",
            "<details class=\"turn\" id=\"turn-2\" open=\"open\">",
            "✓ ok",
            "✕ error",
            "✕ TIMEOUT",
            "up to 3 attempts",
            "no usage returned",
            "2 model calls, 0 repairs")
        .doesNotContain(PHONE);
  }

  private String turnPath(int n) {
    return "/conversations/" + id + "/turns/" + n;
  }

  /** v1 with one line added, saved as the next version; returns its id. */
  private String saveV1WithMarker() {
    PromptVersion v1 = versions.findById("v1").orElseThrow();
    PromptStore.SaveResult saved =
        prompts.save(
            new PromptStore.SaveRequest(
                v1.promptMd() + "\n" + MARKER + "\n",
                v1.knowledgeMd(),
                v1.schemaJson(),
                v1.toolsJson(),
                v1.templatesJson(),
                "inspector test"),
            SettingsFixture.DEFAULTS.persona());
    return ((PromptStore.SaveResult.Saved) saved).id();
  }
}
