package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enrola.anna.AnnaApplication;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.conversation.ConflictException;
import com.enrola.anna.conversation.ConversationService;
import com.enrola.anna.conversation.InboundTurns;
import com.enrola.anna.conversation.Source;
import com.enrola.anna.conversation.TurnLocks;
import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.LeadRepository;
import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.Template;
import com.enrola.anna.prompt.Templates;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;

/**
 * The platform contract over the real H2 file and the seeded leads, on a clock fixed on Sunday 26
 * July 2026, 10:00 in Melbourne, with no model configured. Locks wait 100 ms so BUSY is quick.
 */
@SpringBootTest(
    properties = {
      "anna.turns.lock-wait=100ms",
      "OPENAI_API_KEY=",
      "anna.clock.mode=FIXED",
      "anna.clock.fixed-at=" + ConversationApiTest.FIXED_AT
    })
class ConversationApiTest {

  static final String FIXED_AT = "2026-07-26T00:00:00Z";
  static final Instant NOW = Instant.parse(FIXED_AT);

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void dataInTempDir(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("anna"));
    registry.add("anna.data-dir", tempDir::toString);
  }

  @Autowired WebApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  @Autowired LeadRepository leads;
  @Autowired PromptStore prompts;
  Templates templates;
  @Autowired TurnLocks locks;
  MockMvc mvc;

  @BeforeEach
  void noConversations() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    templates = prompts.bundle("v1", SettingsFixture.DEFAULTS.persona()).templates();
    jdbc.update("delete from trace_event");
    jdbc.update("delete from turn");
    jdbc.update("delete from message");
    jdbc.update("delete from conversation");
    jdbc.update("delete from suppression");
  }

  @Nested
  class Start {

    @Test
    void startReturnsOpenerAndState() throws Exception {
      String opener =
          templates.render(Template.OPENER_PROVIDER, leads.findById("lauren").orElseThrow());

      ResultActions started = start("lauren");

      started
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.conversation_id").value(matchesPattern("c-[0-9a-f-]{36}")))
          .andExpect(jsonPath("$.prompt_version").value("v1"))
          .andExpect(jsonPath("$.reply").value(opener))
          .andExpect(jsonPath("$.stage").value("ENGAGE"))
          .andExpect(jsonPath("$.end_reason").value("NONE"))
          .andExpect(jsonPath("$.flags").isEmpty())
          .andExpect(jsonPath("$.booking").value(nullValue()))
          .andExpect(jsonPath("$.last_offered_slots").isEmpty())
          .andExpect(jsonPath("$.extracted").value(nullValue()))
          .andExpect(jsonPath("$.lead_local_time").value("Sun 26 Jul 10:00 AM AEST"))
          .andExpect(jsonPath("$.trace_url").value("/conversations/" + idOf(started)))
          .andExpect(jsonPath("$.transcript.length()").value(1))
          .andExpect(jsonPath("$.transcript[0].turn").value(0))
          .andExpect(jsonPath("$.transcript[0].direction").value("OUT"))
          .andExpect(jsonPath("$.transcript[0].source").value("TEMPLATE"))
          .andExpect(jsonPath("$.transcript[0].chars").value(opener.length()))
          .andExpect(jsonPath("$.transcript[0].at").value("2026-07-26T00:00:00Z"));
    }

    @Test
    void startRecordsWhatTheConversationRunsOn() throws Exception {
      String id = idOf(start("lauren"));

      assertThat(
              jdbc.queryForMap(
                  "select prompt_version, model, reasoning_effort, app_version, source"
                      + " from conversation where id = ?",
                  id))
          .containsEntry("SOURCE", "API")
          .containsEntry("PROMPT_VERSION", "v1")
          .containsEntry("MODEL", "gpt-5.1")
          .containsEntry("REASONING_EFFORT", "low")
          .hasEntrySatisfying(
              "APP_VERSION",
              version -> assertThat(version.toString()).matches("\\d+\\.\\d+\\.\\d+.*"));
    }

    @Test
    void noProviderLeadGetsFirstTimeOpener() throws Exception {
      String opener =
          templates.render(Template.OPENER_NO_PROVIDER, leads.findById("jane").orElseThrow());

      start("jane").andExpect(status().isCreated()).andExpect(jsonPath("$.reply").value(opener));
    }

    @Test
    void unknownLeadIs404() throws Exception {
      start("nobody")
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.error").value("NOT_FOUND"))
          .andExpect(jsonPath("$.message").value("lead nobody"))
          .andExpect(jsonPath("$.conversation_id").value(nullValue()));
    }

    @Test
    void secondStartIs409WithId() throws Exception {
      String firstId = idOf(start("lauren"));

      start("lauren")
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error").value("ACTIVE"))
          .andExpect(jsonPath("$.conversation_id").value(firstId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"lead_id\":\"\"}", "{\"lead_id\":\" \"}"})
    void blankLeadIdIs400NamingTheWireField(String body) throws Exception {
      send(body, MediaType.APPLICATION_JSON)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("VALIDATION"))
          .andExpect(jsonPath("$.message").value("lead_id must not be blank"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"lead_id\":", "[\"lauren\"]"})
    void unreadableBodyIs400(String body) throws Exception {
      send(body, MediaType.APPLICATION_JSON)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("VALIDATION"))
          .andExpect(jsonPath("$.message").value("request body must be a JSON object"))
          .andExpect(jsonPath("$.conversation_id").value(nullValue()));
    }

    @Test
    void overlongLeadIdIs400() throws Exception {
      start("x".repeat(41))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("VALIDATION"))
          .andExpect(jsonPath("$.message").value("lead_id must be at most 40 characters"));
    }

    @Test
    void nonJsonBodyIs415() throws Exception {
      send("lead_id=lauren", MediaType.APPLICATION_FORM_URLENCODED)
          .andExpect(status().isUnsupportedMediaType())
          .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unknownStateFlagsTzAssumed() throws Exception {
      leads.save(
          new Lead("xavier", "Xavier", "+61400000009", null, "XX", null, null, null, null, NOW));

      String id = idOf(start("xavier").andExpect(jsonPath("$.flags[0]").value("TZ_ASSUMED")));

      assertThat(
              jdbc.queryForObject(
                  "select lead_zone_id from conversation where id = ?", String.class, id))
          .isEqualTo("Australia/Melbourne");
    }

    @Test
    void forceStartsASecondConversationBesideAnOpenOne() throws Exception {
      String first = idOf(start("john"));

      String second = idOf(forceStart("john").andExpect(status().isCreated()));

      assertThat(second).isNotEqualTo(first);
    }

    @Test
    void forceMustBeABoolean() throws Exception {
      mvc.perform(
              post("/api/conversations?force=maybe")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"lead_id\":\"john\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("VALIDATION"))
          .andExpect(jsonPath("$.message").value("force has the wrong type"));
    }

    @Test
    void handedOffConversationStillBlocksAStart() throws Exception {
      String id = idOf(start("lauren"));
      inbound(id, "save money", "p1");

      start("lauren")
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error").value("ACTIVE"))
          .andExpect(jsonPath("$.conversation_id").value(id));
    }

    /** This context has no model to end it HANDOFF, so the row is set directly. */
    @Test
    void endedConversationNoLongerBlocksAStart() throws Exception {
      String id = idOf(start("lauren"));
      jdbc.update(
          "update conversation set ended = true, end_reason = 'HANDOFF', status = 'HANDOFF'"
              + " where id = ?",
          id);

      start("lauren").andExpect(status().isCreated());
    }

    /** Only a deliberate forced start texts a lead who was abusive again. */
    @Test
    void abusiveEndBlocksAPlainStart() throws Exception {
      String id = idOf(start("lauren"));
      jdbc.update(
          "update conversation set ended = true, end_reason = 'ABUSIVE', status = 'ABUSIVE'"
              + " where id = ?",
          id);

      start("lauren")
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error").value("ABUSIVE"))
          .andExpect(jsonPath("$.message").value("lead lauren's last conversation ended abusive"))
          .andExpect(jsonPath("$.conversation_id").value(id));
      forceStart("lauren").andExpect(status().isCreated());
    }

    @Test
    void concurrentStartsOpenOneConversation() throws Exception {
      List<Integer> statuses = concurrently(8, () -> start("john"));

      assertThat(statuses).containsOnlyOnce(201).isSubsetOf(201, 409);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from conversation where lead_id = 'john'", Integer.class))
          .isEqualTo(1);
    }
  }

  @Nested
  class View {

    @Test
    void viewReturnsTranscriptWithNullReply() throws Exception {
      String id = idOf(start("john"));

      mvc.perform(get("/api/conversations/{id}", id))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.conversation_id").value(id))
          .andExpect(jsonPath("$.prompt_version").value("v1"))
          .andExpect(jsonPath("$.reply").value(nullValue()))
          .andExpect(jsonPath("$.no_reply").value(nullValue()))
          .andExpect(jsonPath("$.transcript.length()").value(1))
          .andExpect(jsonPath("$.transcript[0].source").value("TEMPLATE"));
    }

    @Test
    void unknownConversationIs404() throws Exception {
      mvc.perform(get("/api/conversations/{id}", "c-nope"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.error").value("NOT_FOUND"))
          .andExpect(jsonPath("$.message").value("conversation c-nope"));
    }

    @Test
    void malformedConversationIdIs400() throws Exception {
      mvc.perform(get("/api/conversations/{id}", "c-" + "x".repeat(60)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("VALIDATION"));
    }

    /** This context has no model to flag health information, so the row is set directly. */
    @Test
    void healthBodiesAreHiddenUnlessIncludeHealth() throws Exception {
      String id = idOf(start("lauren"));
      inbound(id, "I have a heart condition", "p1");
      jdbc.update(
          "update conversation set health_info_turn = 1, flags_json = '[\"HEALTH_INFO\"]'"
              + " where id = ?",
          id);

      mvc.perform(get("/api/conversations/{id}", id))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.transcript[0].text").value(startsWith("Hi Lauren")))
          .andExpect(jsonPath("$.transcript[0].redacted").doesNotExist())
          .andExpect(jsonPath("$.transcript[1].text").value(nullValue()))
          .andExpect(jsonPath("$.transcript[1].redacted").value("HEALTH_INFO"));
      mvc.perform(get("/api/conversations/{id}?includeHealth=true", id))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.transcript[1].text").value("I have a heart condition"))
          .andExpect(jsonPath("$.transcript[1].redacted").doesNotExist());
    }

    @Test
    void includeHealthMustBeABoolean() throws Exception {
      mvc.perform(get("/api/conversations/{id}?includeHealth=maybe", idOf(start("john"))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("VALIDATION"))
          .andExpect(jsonPath("$.message").value("includeHealth has the wrong type"));
    }
  }

  @Nested
  class Inbound {

    @Test
    void inboundWithoutModelIsStoredAndHandedOff() throws Exception {
      String id = idOf(start("lauren"));

      inbound(id, "save money", "p1")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reply").value(nullValue()))
          .andExpect(jsonPath("$.no_reply").value("HANDOFF"))
          .andExpect(jsonPath("$.needs_human").value(true))
          .andExpect(jsonPath("$.handoff_reason").value("ERROR"))
          .andExpect(jsonPath("$.conversation_ended").value(false))
          .andExpect(jsonPath("$.end_reason").value("NONE"))
          .andExpect(jsonPath("$.transcript.length()").value(2))
          .andExpect(jsonPath("$.transcript[1].turn").value(1))
          .andExpect(jsonPath("$.transcript[1].direction").value("IN"))
          .andExpect(jsonPath("$.transcript[1].text").value("save money"));
      assertThat(statusOf(id)).isEqualTo("HANDOFF");
      assertThat(turnStatus(id, 1)).isEqualTo("NO_REPLY");
    }

    @Test
    void duplicateProviderIdReplaysTheStoredResponse() throws Exception {
      String id = idOf(start("lauren"));
      String first = body(inbound(id, "save money", "p1"));

      String second = body(inbound(id, "save money", "p1").andExpect(status().isOk()));

      JSONAssert.assertEquals(first, second, JSONCompareMode.STRICT);
      assertThat(count("message", id)).isEqualTo(2);
      assertThat(count("turn", id)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesStoreOneTurn() throws Exception {
      String id = idOf(start("lauren"));

      List<Integer> statuses = concurrently(2, () -> inbound(id, "save money", "p1"));

      assertThat(statuses).isSubsetOf(200, 409);
      assertThat(count("message", id)).isEqualTo(2);
      assertThat(count("turn", id)).isEqualTo(1);
    }

    /** A crash between claim and finish leaves an inbound row with no turn; the retry runs it. */
    @Test
    void claimedButUnfinishedTurnRunsOnRetry() throws Exception {
      String id = idOf(start("lauren"));
      jdbc.update(
          "insert into message (id, conversation_id, turn_no, direction, text, char_count,"
              + " provider_message_id, created_at) values ('m-orphan', ?, 1, 'IN', 'hi', 2, 'p9', ?)",
          id,
          NOW);

      inbound(id, "hi", "p9")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.handoff_reason").value("ERROR"));

      assertThat(count("message", id)).isEqualTo(2);
      assertThat(
              jdbc.queryForList(
                  "select inbound_message_id from turn where conversation_id = ? and turn_no = 1",
                  String.class,
                  id))
          .containsExactly("m-orphan");
    }

    /** The unique index on (conversation_id, provider_message_id) must let nulls repeat. */
    @Test
    void messagesWithoutProviderIdAreEachStored() throws Exception {
      String id = idOf(start("lauren"));

      inbound(id, "one", null).andExpect(status().isOk());
      inbound(id, "two", null).andExpect(status().isOk());

      assertThat(
              jdbc.queryForList(
                  "select turn_no from message where conversation_id = ? and direction = 'IN'"
                      + " order by turn_no",
                  Integer.class,
                  id))
          .containsExactly(1, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"text\":\"  \"}"})
    void blankTextIs400(String body) throws Exception {
      postMessage(idOf(start("lauren")), body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("VALIDATION"))
          .andExpect(jsonPath("$.message").value("text must not be blank"));
    }

    @Test
    void textOverTheTransportCapIs400() throws Exception {
      inbound(idOf(start("lauren")), "a".repeat(4001), "p1")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("text must be at most 4000 characters"));
    }

    /** A blank id would make unrelated messages replay each other's responses. */
    @ParameterizedTest
    @ValueSource(
        strings = {
          "",
          "  ",
          "a b",
          "ppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp"
        })
    void blankSpacedOrOverlongProviderIdIs400(String providerMessageId) throws Exception {
      inbound(idOf(start("lauren")), "STOP", providerMessageId)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("VALIDATION"))
          .andExpect(
              jsonPath("$.message")
                  .value("provider_message_id must be 1 to 80 characters with no spaces"));
    }

    @ParameterizedTest(name = "{0} chars flagged: {1}")
    @CsvSource({"1600, false", "1601, true"})
    void inboundOverTheLimitIsStoredWholeAndFlagged(int length, boolean flagged) throws Exception {
      String id = idOf(start("lauren"));

      inbound(id, "a".repeat(length), "p4")
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.flags")
                  .value(flagged ? hasItem("INPUT_TRUNCATED") : not(hasItem("INPUT_TRUNCATED"))))
          .andExpect(jsonPath("$.transcript[1].chars").value(length));
    }

    @Test
    void conversationMidTurnIs409Busy() throws Exception {
      String id = idOf(start("lauren"));
      CountDownLatch held = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      Thread holder =
          new Thread(
              () ->
                  locks.forConversation(
                      id,
                      () -> {
                        held.countDown();
                        awaitQuietly(release);
                        return null;
                      }));
      holder.start();
      held.await();
      try {
        inbound(id, "hello", "p5")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("BUSY"))
            .andExpect(jsonPath("$.message").value("conversation " + id + " is processing a turn"))
            .andExpect(jsonPath("$.conversation_id").value(id));
      } finally {
        release.countDown();
        holder.join();
      }
    }

    @Test
    void messageToUnknownConversationIs404() throws Exception {
      inbound("c-nope", "hello", "p1")
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("conversation c-nope"));
    }

    @Test
    void messageToMalformedConversationIdIs400() throws Exception {
      inbound("c-" + "x".repeat(60), "hello", "p1")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("VALIDATION"));
    }
  }

  @Nested
  class OptOut {

    @Test
    void stopUnsubscribesAndSuppressesWithoutModel() throws Exception {
      String id = idOf(start("lauren"));
      String confirmation =
          templates.render(
              Template.UNSUBSCRIBE_CONFIRMATION, leads.findById("lauren").orElseThrow());

      inbound(id, "Stop.", "p2")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reply").value(confirmation))
          .andExpect(jsonPath("$.unsubscribed").value(true))
          .andExpect(jsonPath("$.conversation_ended").value(true))
          .andExpect(jsonPath("$.end_reason").value("UNSUBSCRIBED"))
          .andExpect(jsonPath("$.needs_human").value(false))
          .andExpect(jsonPath("$.end_reason_history.length()").value(1))
          .andExpect(jsonPath("$.end_reason_history[0].reason").value("UNSUBSCRIBED"))
          .andExpect(jsonPath("$.end_reason_history[0].at").value("2026-07-26T00:00:00Z"))
          .andExpect(jsonPath("$.transcript[2].direction").value("OUT"))
          .andExpect(jsonPath("$.transcript[2].source").value("FIXED"));
      assertThat(turnStatus(id, 1)).isEqualTo("OK");
      assertThat(
              jdbc.queryForMap(
                  "select reason, lead_id from suppression where phone = '+61400000001'"))
          .containsEntry("REASON", "KEYWORD")
          .containsEntry("LEAD_ID", "lauren");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hello?", "STOP"})
    void laterMessagesAfterOptOutAreStoredSilently(String text) throws Exception {
      String id = idOf(start("lauren"));
      inbound(id, "STOP", "p2");

      inbound(id, text, "p3")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reply").value(nullValue()))
          .andExpect(jsonPath("$.end_reason_history.length()").value(1))
          .andExpect(jsonPath("$.transcript[?(@.source == 'FIXED')]").value(hasSize(1)))
          .andExpect(jsonPath("$.transcript[3].direction").value("IN"))
          .andExpect(jsonPath("$.transcript[3].text").value(text));
      assertThat(turnStatus(id, 2)).isEqualTo("NO_REPLY");
    }

    /** This context has no moderation or model to end it ABUSIVE, so the row is set directly. */
    @Test
    void hardTerminalConversationAnswersNothing() throws Exception {
      String id = idOf(start("lauren"));
      jdbc.update(
          "update conversation set ended = true, end_reason = 'ABUSIVE', status = 'ABUSIVE'"
              + " where id = ?",
          id);

      inbound(id, "hello?", "p3")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reply").value(nullValue()))
          .andExpect(jsonPath("$.no_reply").value("ENDED_BEFORE"))
          .andExpect(jsonPath("$.end_reason").value("ABUSIVE"))
          .andExpect(jsonPath("$.handoff_reason").value("NONE"))
          .andExpect(jsonPath("$.transcript[?(@.direction == 'OUT')]").value(hasSize(1)));
    }

    /** Compliance beats silence: an opt-out outranks the abusive end and is confirmed. */
    @Test
    void stopAfterAHardEndUnsubscribesAndConfirms() throws Exception {
      String id = idOf(start("lauren"));
      jdbc.update(
          "update conversation set ended = true, end_reason = 'ABUSIVE', status = 'ABUSIVE'"
              + " where id = ?",
          id);

      inbound(id, "STOP", "p3")
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.reply")
                  .value(
                      templates.render(
                          Template.UNSUBSCRIBE_CONFIRMATION,
                          leads.findById("lauren").orElseThrow())))
          .andExpect(jsonPath("$.end_reason").value("UNSUBSCRIBED"))
          .andExpect(jsonPath("$.unsubscribed").value(true));
      assertThat(
              jdbc.queryForObject(
                  "select reason from suppression where phone = '+61400000001'", String.class))
          .isEqualTo("KEYWORD");
      start("lauren")
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error").value("SUPPRESSED"));
    }

    @Test
    void optOutSilencesTheSamePhonesOtherConversation() throws Exception {
      String first = idOf(start("lauren"));
      String second = idOf(forceStart("lauren"));
      inbound(first, "STOP", "p2");

      inbound(second, "yes keen", "q1")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reply").value(nullValue()))
          .andExpect(jsonPath("$.unsubscribed").value(true))
          .andExpect(jsonPath("$.end_reason").value("UNSUBSCRIBED"))
          .andExpect(jsonPath("$.handoff_reason").value("NONE"))
          .andExpect(jsonPath("$.transcript[?(@.direction == 'OUT')]").value(hasSize(1)));
      assertThat(turnStatus(second, 1)).isEqualTo("NO_REPLY");
    }

    @ParameterizedTest(name = "force={0}")
    @ValueSource(booleans = {false, true})
    void optedOutPhoneCannotStart(boolean force) throws Exception {
      inbound(idOf(start("lauren")), "STOP", "p2");

      (force ? forceStart("lauren") : start("lauren"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error").value("SUPPRESSED"))
          .andExpect(jsonPath("$.message").value("phone is opted out"))
          .andExpect(jsonPath("$.conversation_id").value(nullValue()));
    }
  }

  @Nested
  class Restart {

    /**
     * The two-day-pause proof: a conversation and an opt-out made by one process hold in the next.
     */
    @Test
    void conversationAndOptOutSurviveRestart(@TempDir Path dir) {
      String url = "jdbc:h2:file:" + dir.resolve("anna");
      String id;
      try (ConfigurableApplicationContext first = boot(url)) {
        id = first.getBean(ConversationService.class).start("jane", Source.API).conversationId();
        first.getBean(InboundTurns.class).handle(id, "STOP", "r1");
      }
      try (ConfigurableApplicationContext second = boot(url)) {
        int port = second.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        String view =
            RestClient.create()
                .get()
                .uri("http://localhost:{port}/api/conversations/{id}", port, id)
                .retrieve()
                .body(String.class);

        assertThat(JsonPath.<Boolean>read(view, "$.unsubscribed")).isTrue();
        assertThatThrownBy(() -> second.getBean(ConversationService.class).forceStart("jane"))
            .isInstanceOfSatisfying(
                ConflictException.class, e -> assertThat(e.code()).isEqualTo("SUPPRESSED"));
      }
    }

    private static ConfigurableApplicationContext boot(String datasourceUrl) {
      return new SpringApplicationBuilder(AnnaApplication.class)
          .run("--spring.datasource.url=" + datasourceUrl, "--server.port=0", "--OPENAI_API_KEY=");
    }
  }

  private ResultActions start(String leadId) throws Exception {
    return send("{\"lead_id\":\"" + leadId + "\"}", MediaType.APPLICATION_JSON);
  }

  private ResultActions forceStart(String leadId) throws Exception {
    return mvc.perform(
        post("/api/conversations?force=true")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"lead_id\":\"" + leadId + "\"}"));
  }

  private ResultActions send(String body, MediaType contentType) throws Exception {
    return mvc.perform(post("/api/conversations").contentType(contentType).content(body));
  }

  private ResultActions inbound(String conversationId, String text, String providerMessageId)
      throws Exception {
    String providerId =
        providerMessageId == null ? "" : ",\"provider_message_id\":\"" + providerMessageId + "\"";
    return postMessage(conversationId, "{\"text\":\"" + text + "\"" + providerId + "}");
  }

  private ResultActions postMessage(String conversationId, String body) throws Exception {
    return mvc.perform(
        post("/api/conversations/{id}/messages", conversationId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  /** Releases {@code threads} copies of {@code request} at once; returns their HTTP statuses. */
  private static List<Integer> concurrently(int threads, Callable<ResultActions> request)
      throws Exception {
    CountDownLatch gate = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      List<Future<Integer>> pending = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        pending.add(
            pool.submit(
                () -> {
                  gate.await();
                  return request.call().andReturn().getResponse().getStatus();
                }));
      }
      gate.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> status : pending) {
        statuses.add(status.get());
      }
      return statuses;
    }
  }

  private String statusOf(String conversationId) {
    return jdbc.queryForObject(
        "select status from conversation where id = ?", String.class, conversationId);
  }

  private String turnStatus(String conversationId, int turnNo) {
    return jdbc.queryForObject(
        "select status from turn where conversation_id = ? and turn_no = ?",
        String.class,
        conversationId,
        turnNo);
  }

  /** Rows in {@code table} for the conversation; the table name is a constant from the caller. */
  private int count(String table, String conversationId) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where conversation_id = ?",
        Integer.class,
        conversationId);
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String body(ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString();
  }

  private static String idOf(ResultActions started) throws Exception {
    return JsonPath.read(body(started), "$.conversation_id");
  }
}
