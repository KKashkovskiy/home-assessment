package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.Decisions;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.ScriptedModelClient;
import com.enrola.anna.conversation.InboundTurns;
import com.enrola.anna.conversation.TurnLocks;
import com.enrola.anna.guard.ModerationVerdict;
import com.enrola.anna.guard.ScriptedModerationClient;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The Conversations page with the model and moderation scripted: list, start, send, reset. Pages
 * are checked by their structure and the codes they carry, not by their wording.
 */
@Import(ConsoleFixture.Scripted.class)
class ConversationsControllerTest extends ConsoleFixture {

  static final String QUESTION = "Fair enough. Hospital, extras or both?";

  @Autowired ScriptedModelClient model;
  @Autowired ScriptedModerationClient moderation;
  @Autowired InboundTurns turns;
  @Autowired TurnLocks locks;

  @BeforeEach
  void nothingScripted() {
    model.reset();
    moderation.reset();
  }

  @Nested
  class ConversationList {

    @Test
    void listShowsStartedRow() throws Exception {
      start("lauren");
      start("john");

      String page = body(mvc.perform(get("/conversations?q=lauren")).andExpect(status().isOk()));

      assertThat(rows(page)).isEqualTo(1);
      assertThat(page).contains("id=\"conv-count\" class=\"muted small\">1 of 2<");
      assertThat(row(page, "Lauren")).contains(">VIC<", ">Engage<", "ACTIVE</span>", ">SIM<");
    }

    @Test
    void filterByEndReasonNarrowsList() throws Exception {
      String lauren = start("lauren");
      start("john");
      model.decide(Decisions.engaged().ends(EndReason.DECLINED).reply("No worries.").build());
      turns.handle(lauren, "no thanks", null);

      String page = body(mvc.perform(get("/conversations?endReason=DECLINED")));

      assertThat(rows(page)).isEqualTo(1);
      assertThat(page).contains("href=\"/conversations\" title=\"Remove the end reason filter\"");
      assertThat(rows(body(mvc.perform(get("/conversations"))))).isEqualTo(2);
    }

    @Test
    void filtersAndCombine() throws Exception {
      String lauren = start("lauren");
      start("john");
      model.decide(Decisions.engaged().flags(Flag.HEALTH_INFO).reply(QUESTION).build());
      turns.handle(lauren, "I was just diagnosed", null);

      assertThat(rows(body(mvc.perform(get("/conversations?status=ACTIVE&flag=HEALTH_INFO")))))
          .isEqualTo(1);
      assertThat(rows(body(mvc.perform(get("/conversations?status=ACTIVE"))))).isEqualTo(2);
      assertThat(rows(body(mvc.perform(get("/conversations?status=ACTIVE&source=API"))))).isZero();
    }

    @Test
    void searchMatchesSummary() throws Exception {
      String lauren = start("lauren");
      start("john");
      model.decide(Decisions.engaged().summary("Wants hospital cover").reply(QUESTION).build());
      turns.handle(lauren, "hospital please", null);

      String page = body(mvc.perform(get("/conversations?q=HOSPITAL")));

      assertThat(rows(page)).isEqualTo(1);
      assertThat(row(page, "Lauren")).contains("Wants hospital cover");
    }

    @Test
    void healthInfoRowIsBadgedAndItsSummaryFactsAndBodiesHidden() throws Exception {
      String id = start("lauren");
      model.decide(
          Decisions.engaged()
              .flags(Flag.HEALTH_INFO)
              .summary("Diagnosed with diabetes, wants cover")
              .extracted(withCoverNote("diabetes cover"))
              .reply(QUESTION)
              .build());
      turns.handle(id, "I was just diagnosed with diabetes", null);

      String page = body(mvc.perform(get("/conversations/" + id)));

      assertThat(row(page, "Lauren")).contains("⚑ HEALTH INFO", PiiMask.HIDDEN);
      assertThat(page).contains(PiiMask.HIDDEN, "Hi Lauren").doesNotContain("diabetes", QUESTION);
    }

    @Test
    void summaryAndCoverNoteAreMaskedLikeMessages() throws Exception {
      String id = start("lauren");
      model.decide(
          Decisions.engaged()
              .summary("Call her on 0498 765 432")
              .extracted(withCoverNote("Bupa, ask for lauren@example.com"))
              .reply(QUESTION)
              .build());
      turns.handle(id, "save money", null);

      String page = body(mvc.perform(get("/conversations/" + id)));

      assertThat(page).contains("Call her on 049• ••• 432", "Bupa, ask for ***@***");
      assertThat(page).doesNotContain("0498 765 432", "lauren@example.com");
    }

    @Test
    void unknownFilterValueOrFlagIs400InTheShell() throws Exception {
      for (String url :
          new String[] {"/conversations?status=BOGUS", "/conversations?start=maybe"}) {
        assertThat(body(mvc.perform(get(url)).andExpect(status().isBadRequest())))
            .contains("data-notice=\"BAD_REQUEST\"", "class=\"tabs\"")
            .doesNotContain("BOGUS", "maybe");
      }
    }

    @Test
    void missingOrMalformedConversationIs404InTheShell() throws Exception {
      assertThat(body(mvc.perform(get("/conversations/c-nope")).andExpect(status().isNotFound())))
          .contains("data-notice=\"NOT_FOUND\"", "class=\"tabs\"")
          .doesNotContain("c-nope");
      assertThat(body(mvc.perform(get("/conversations/c_<b>")).andExpect(status().isNotFound())))
          .contains("data-notice=\"NOT_FOUND\"")
          .doesNotContain("c_<b>");
    }

    @Test
    void unknownErrorCodeIsNeverShown() throws Exception {
      assertThat(body(mvc.perform(get("/conversations").param("error", "<script>x</script>"))))
          .doesNotContain("x</script>", "data-notice");
    }

    private static Decision.Extracted withCoverNote(String note) {
      return new Decision.Extracted(
          Decision.CoverageGoal.UNKNOWN,
          Decision.CoverType.UNKNOWN,
          note,
          null,
          Decision.TimeOfDay.UNKNOWN);
    }
  }

  @Nested
  class Start {

    @Test
    void startLaurenRedirectsToHerOpener() throws Exception {
      String location = startForm("lead", "lauren");

      String page = body(mvc.perform(get(location)).andExpect(status().isOk()));

      assertThat(location).matches("/conversations/c-[0-9a-f-]{36}");
      assertThat(page)
          .contains("class=\"bubble agent\"", "Hi Lauren", ">TEMPLATE<")
          .contains("goal met: <b>no</b>", "data-at-limit=\"false\"", "<b>0/15</b>")
          .contains("class=\"current\" aria-current=\"step\"", "data-kind=\"READY\"")
          .doesNotContain("data-at-limit=\"true\"");
    }

    @Test
    void customLeadStartsWithItsConsentAndIsReusedByPhone() throws Exception {
      String location =
          startForm(
              "lead", "custom",
              "custom.givenName", "Pat",
              "custom.phone", "0412 345 678",
              "custom.state", "QLD",
              "custom.consentSource", "enquiry form, 2 Aug");

      assertThat(body(mvc.perform(get(location))))
          .contains("Hi Pat", "enquiry form, 2 Aug", "+61••••••678", "QLD → Australia/Brisbane");
      assertThat(
              jdbc.queryForObject("select phone from lead where given_name = 'Pat'", String.class))
          .isEqualTo("+61412345678");
      assertThat(
              startForm(
                  "lead", "custom", "custom.givenName", "Patricia", "custom.phone", "+61412345678"))
          .isEqualTo(location + "?error=ACTIVE");
      assertThat(count("select count(*) from lead where phone = '+61412345678'")).isEqualTo(1);
    }

    /** Lauren's phone typed as a custom lead starts Lauren, and the page says the name went. */
    @Test
    void customLeadWithAKnownPhoneStartsThatLeadAndSaysSo() throws Exception {
      String location =
          startForm("lead", "custom", "custom.givenName", "Laura", "custom.phone", "0400 000 001");

      assertThat(location).endsWith("?error=" + Notice.LEAD_REUSED);
      assertThat(body(mvc.perform(get(location))))
          .contains("data-notice=\"LEAD_REUSED\"", "Hi Lauren")
          .doesNotContain("Laura,");
      assertThat(count("select count(*) from lead where given_name = 'Laura'")).isZero();
    }

    @Test
    void invalidCustomLeadComesBackWithFieldErrors() throws Exception {
      int leads = count("select count(*) from lead");

      String page =
          body(
              mvc.perform(
                      post("/conversations/start")
                          .param("lead", "custom")
                          .param("custom.givenName", "Pat")
                          .param("custom.phone", "+447700900000")
                          .param("custom.state", "XYZ"))
                  .andExpect(status().isOk()));

      assertThat(page)
          .contains("id=\"c-phone-error\"", "aria-describedby=\"c-phone-error\"")
          .contains("class=\"field invalid\"><label for=\"c-state\">")
          .doesNotContain("id=\"c-name-error\"")
          .doesNotContain("id=\"start-panel\" class=\"start-panel\" hidden");
      assertThat(count("select count(*) from lead")).isEqualTo(leads);
    }

    @Test
    void suppressedLeadIsRefused() throws Exception {
      turns.handle(start("lauren"), "STOP", null);

      assertThat(startForm("lead", "lauren")).isEqualTo("/conversations?error=SUPPRESSED");
      assertThat(count("select count(*) from conversation")).isEqualTo(1);
      assertThat(body(mvc.perform(get("/conversations?error=SUPPRESSED"))))
          .contains("data-notice=\"SUPPRESSED\"");
    }

    @Test
    void unknownLeadIs404InTheShell() throws Exception {
      assertThat(
              body(
                  mvc.perform(post("/conversations/start").param("lead", "nobody"))
                      .andExpect(status().isNotFound())))
          .contains("data-notice=\"NOT_FOUND\"", "class=\"tabs\"")
          .doesNotContain("nobody");
    }
  }

  @Nested
  class Send {

    @Test
    void sendReturnsTheTurnAsAFragment() throws Exception {
      String id = start("lauren");
      model.decide(Decisions.engaged().reply(QUESTION).build());

      String fragment = body(composer(id, "hi").andExpect(status().isOk()));

      assertThat(fragment)
          .containsOnlyOnce("class=\"bubble lead\"")
          .containsOnlyOnce("class=\"bubble agent\"")
          .contains(QUESTION, ">LLM<", "data-turn=\"1\"", "data-hint-kind=\"READY\"")
          .doesNotContain("<html");
    }

    @Test
    void threadLinksEachTurnToItsInspectorCard() throws Exception {
      String id = start("lauren");
      model.decide(Decisions.engaged().reply(QUESTION).build());
      turns.handle(id, "hi", null);

      String page = body(mvc.perform(get("/conversations/" + id)).andExpect(status().isOk()));

      assertThat(page).contains("href=\"#turn-1\" data-turn=\"1\"", "id=\"turn-1\"");
    }

    @Test
    void toolChipsSitBetweenTheBubbles() throws Exception {
      String id = start("lauren");
      model
          .callTool(
              "get_availability",
              "{\"date_from\":\"2026-07-27\",\"date_to\":\"2026-07-27\",\"part_of_day\":\"ANY\"}")
          .decide(Decisions.engaged().reply("Would a morning or an afternoon suit you?").build());

      String fragment = body(composer(id, "monday?"));

      assertThat(fragment).containsPattern("get_availability: \\d+ slots");
      assertThat(fragment.indexOf("bubble lead"))
          .isLessThan(fragment.indexOf("tool-line"))
          .isLessThan(fragment.indexOf("bubble agent"));
    }

    @Test
    void repairedAndFallbackRepliesAreMarked() throws Exception {
      String id = start("lauren");
      String tooLong = "x".repeat(399) + "?";
      model
          .decide(Decisions.engaged().reply(tooLong).build())
          .decide(Decisions.engaged().reply(QUESTION).build());
      model
          .decide(Decisions.engaged().reply(tooLong).build())
          .decide(Decisions.engaged().reply(tooLong).build());

      assertThat(body(composer(id, "save money"))).contains("↻ repaired", ">LLM<");
      assertThat(body(composer(id, "both")))
          .contains("class=\"chip warn\" title=\"What wrote this reply\">FALLBACK<")
          .doesNotContain("↻ repaired", ">FIXED<");
    }

    @Test
    void failedModelCallShowsAHandoffLine() throws Exception {
      String id = start("lauren");
      model.enqueue(Decisions.failed(ModelResponse.Failure.HTTP_5XX));

      assertThat(body(composer(id, "hi")))
          .contains("data-reason=\"HANDOFF\"", "(ERROR)", "data-hint-kind=\"HANDED_OFF\"")
          .doesNotContain("bubble agent");
    }

    @Test
    void messagesAfterAnAbusiveEndShowWhyNothingWasSent() throws Exception {
      String id = start("lauren");
      moderation.enqueue(new ModerationVerdict(0.9, 0, 0, true));

      assertThat(body(composer(id, "you useless pile of junk")))
          .contains("data-reason=\"ENDED_NOW\"", "(ABUSIVE)", "data-hint-kind=\"SILENT\"");
      assertThat(body(composer(id, "hello?")))
          .contains("data-reason=\"ENDED_BEFORE\"", "(ABUSIVE)")
          .doesNotContain("bubble agent");
    }

    /**
     * Only the turn that suppressed a repeat says so; the flag it leaves does not relabel later
     * ones.
     */
    @Test
    void eachSilentTurnKeepsItsOwnReason() throws Exception {
      String id = start("lauren");
      model.decide(Decisions.engaged().ends(EndReason.DECLINED).reply("No worries.").build());
      turns.handle(id, "no thanks", null);
      model.decide(Decisions.engaged().reply("Take care.").build());
      turns.handle(id, "ok", null);
      model.decide(Decisions.engaged().noReply().build());

      assertThat(body(composer(id, "thanks"))).contains("data-reason=\"MODEL_SILENT\"");
      assertThat(body(mvc.perform(get("/conversations/" + id))))
          .containsOnlyOnce("data-reason=\"CLOSING_SUPPRESSED\"")
          .containsOnlyOnce("data-reason=\"MODEL_SILENT\"");
    }

    @Test
    void withoutScriptSendRedirectsAndAQuickReplyChipSendsItsText() throws Exception {
      String id = start("lauren");
      model.decide(Decisions.engaged().reply(QUESTION).build());

      mvc.perform(post("/conversations/" + id + "/send").param("text", "hi"))
          .andExpect(status().is3xxRedirection())
          .andExpect(header().string("Location", "/conversations/" + id));
      mvc.perform(post("/conversations/" + id + "/send").param("text", "").param("quick", "STOP"))
          .andExpect(status().is3xxRedirection());

      assertThat(body(mvc.perform(get("/conversations/" + id))))
          .contains(QUESTION, ">FIXED<", "unsubscribed: <b>yes</b>", "UNSUBSCRIBED</span>");
    }

    @Test
    void emptyOrOversizedMessageIs400AndStoresNothing() throws Exception {
      String id = start("lauren");

      body(composer(id, "   ").andExpect(status().isBadRequest()));
      body(
          composer(id, "x".repeat(InboundRequest.MAX_TEXT + 1)).andExpect(status().isBadRequest()));

      assertThat(count("select count(*) from message where conversation_id = ?", id)).isEqualTo(1);
    }

    @Test
    void sendWhileATurnRunsIs409() throws Exception {
      String id = start("lauren");
      CountDownLatch held = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      Thread holder =
          Thread.ofPlatform()
              .start(() -> locks.forConversation(id, () -> holdUntil(held, release)));
      held.await();
      try {
        assertThat(body(composer(id, "hello").andExpect(status().isConflict())))
            .contains("class=\"system-line problem\"")
            .doesNotContain("<html");
      } finally {
        release.countDown();
        holder.join();
      }
      assertThat(count("select count(*) from message where conversation_id = ?", id)).isEqualTo(1);
    }

    private ResultActions composer(String id, String text) throws Exception {
      MockHttpServletRequestBuilder send =
          post("/conversations/" + id + "/send")
              .param("text", text)
              .header("X-Requested-With", "fetch");
      return mvc.perform(send);
    }

    private static Object holdUntil(CountDownLatch held, CountDownLatch release) {
      held.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return null;
    }
  }

  @Nested
  class CrossSite {

    @Test
    void onlyPostsFromThisOriginAreTaken() throws Exception {
      String id = start("lauren");

      for (String[] header :
          new String[][] {
            {"Sec-Fetch-Site", "cross-site"},
            {"Sec-Fetch-Site", "same-site"},
            {"Origin", "https://evil.example"},
            {"Origin", "null"}
          }) {
        mvc.perform(stop(id).header(header[0], header[1])).andExpect(status().isForbidden());
      }
      assertThat(count("select count(*) from suppression")).isZero();

      mvc.perform(
              stop(id).header("Origin", "http://console.example").header("Host", "console.example"))
          .andExpect(status().is3xxRedirection());
      assertThat(count("select count(*) from suppression")).isEqualTo(1);
    }

    @Test
    void sameOriginOrTypedRequestsPass() throws Exception {
      String id = start("lauren");

      mvc.perform(get("/conversations/" + id).header("Sec-Fetch-Site", "cross-site"))
          .andExpect(status().isOk());
      mvc.perform(post("/ui/pii-mask").param("on", "true").header("Sec-Fetch-Site", "same-origin"))
          .andExpect(status().is3xxRedirection());
      mvc.perform(post("/ui/pii-mask").param("on", "true").header("Sec-Fetch-Site", "none"))
          .andExpect(status().is3xxRedirection());
    }

    @Test
    void pagesRefuseToBeFramed() throws Exception {
      mvc.perform(get("/conversations"))
          .andExpect(header().string("X-Frame-Options", "DENY"))
          .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'none'"));
    }

    private MockHttpServletRequestBuilder stop(String id) {
      return post("/conversations/" + id + "/send").param("text", "STOP");
    }
  }

  @Nested
  class Reset {

    @Test
    void resetStartsOverAndKeepsTheOldConversation() throws Exception {
      String old = start("lauren");

      String location = reset(old);

      String fresh = location.substring("/conversations/".length());
      assertThat(fresh).isNotEqualTo(old);
      assertThat(count("select count(*) from conversation where lead_id = 'lauren'")).isEqualTo(2);
      assertThat(statusOf(old)).isEqualTo("RESET");
      assertThat(body(mvc.perform(get(location)))).contains("Hi Lauren", "RESET</span>");
    }

    /** The old row is history: a message to it is stored, never reaches the model, and says so. */
    @Test
    void messageToAResetConversationIsStoredInSilence() throws Exception {
      String old = start("lauren");
      reset(old);

      String fragment =
          body(
              mvc.perform(
                  post("/conversations/" + old + "/send")
                      .param("text", "hello?")
                      .header("X-Requested-With", "fetch")));

      assertThat(fragment).contains("data-reason=\"RESET\"", "data-hint-kind=\"SILENT\"");
      assertThat(model.calls()).isEmpty();
      assertThat(count("select count(*) from message where conversation_id = ?", old)).isEqualTo(2);
    }

    @Test
    void resetNeverErasesAnOptOut() throws Exception {
      String old = start("lauren");
      turns.handle(old, "STOP", null);

      assertThat(reset(old)).isEqualTo("/conversations/" + old + "?error=SUPPRESSED");
      assertThat(count("select count(*) from suppression where phone = '+61400000001'"))
          .isEqualTo(1);
      assertThat(count("select count(*) from conversation")).isEqualTo(1);
      assertThat(statusOf(old)).isEqualTo("UNSUBSCRIBED");
    }

    /**
     * One transaction: a refused start leaves the open conversation as it was. Lauren opts out on a
     * second conversation the platform forced, so the first is still open when the reset asks.
     */
    @Test
    void refusedResetLeavesTheOpenConversationOpen() throws Exception {
      String old = start("lauren");
      turns.handle(conversations.forceStart("lauren").conversationId(), "STOP", null);

      assertThat(reset(old)).isEqualTo("/conversations/" + old + "?error=SUPPRESSED");
      assertThat(statusOf(old)).isEqualTo("ACTIVE");
    }

    private String reset(String id) throws Exception {
      return mvc.perform(post("/conversations/" + id + "/reset"))
          .andExpect(status().is3xxRedirection())
          .andReturn()
          .getResponse()
          .getHeader("Location");
    }

    private String statusOf(String id) {
      return jdbc.queryForObject("select status from conversation where id = ?", String.class, id);
    }
  }

  @Test
  void placeholderTabsRenderInTheShell() throws Exception {
    for (String tab : new String[] {"configuration", "analytics", "evals"}) {
      assertThat(body(mvc.perform(get("/" + tab)).andExpect(status().isOk())))
          .contains(
              "href=\"/" + tab + "\" aria-current=\"page\"", "class=\"panel placeholder-panel\"");
    }
    mvc.perform(get("/"))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location", "/conversations"));
  }

  private String startForm(String... nameValuePairs) throws Exception {
    MockHttpServletRequestBuilder form = post("/conversations/start");
    for (int i = 0; i < nameValuePairs.length; i += 2) {
      form.param(nameValuePairs[i], nameValuePairs[i + 1]);
    }
    return mvc.perform(form)
        .andExpect(status().is3xxRedirection())
        .andReturn()
        .getResponse()
        .getHeader("Location");
  }

  private static int rows(String page) {
    return page.split("class=\"conv-row\"", -1).length - 1;
  }

  /** The list row of the lead named {@code name}: from its link to the end of the row. */
  private static String row(String page, String name) {
    int start = page.lastIndexOf("class=\"conv-row\"", page.indexOf("class=\"name\">" + name));
    return page.substring(start, page.indexOf("</a>", start));
  }
}
