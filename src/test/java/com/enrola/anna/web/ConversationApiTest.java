package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enrola.anna.AnnaApplication;
import com.enrola.anna.conversation.ConversationService;
import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.LeadRepository;
import com.enrola.anna.prompt.Template;
import com.enrola.anna.prompt.Templates;
import com.enrola.anna.scheduling.LeadZones;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
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
 * The platform contract over the real H2 file and the seeded leads, on a clock pinned to Sunday 26
 * July 2026, 10:00 in Melbourne.
 */
@SpringBootTest
class ConversationApiTest {

  static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void databaseInTempDir(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("anna"));
  }

  @TestConfiguration
  static class FixedClock {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  @Autowired WebApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  @Autowired LeadRepository leads;
  @Autowired Templates templates;
  MockMvc mvc;

  @BeforeEach
  void noConversations() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    jdbc.update("delete from message");
    jdbc.update("delete from conversation");
  }

  @Test
  void startReturnsOpenerAndState() throws Exception {
    String opener =
        templates.render(Template.OPENER_PROVIDER, leads.findById("lauren").orElseThrow());

    ResultActions started = start("lauren");

    started
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.conversation_id").value(matchesPattern("c-[0-9a-f-]{36}")))
        .andExpect(jsonPath("$.reply").value(opener))
        .andExpect(jsonPath("$.stage").value("ENGAGE"))
        .andExpect(jsonPath("$.end_reason").value("NONE"))
        .andExpect(jsonPath("$.flags").isEmpty())
        .andExpect(jsonPath("$.booking").value(nullValue()))
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
        .isEqualTo(LeadZones.ADVISOR.getId());
  }

  @Test
  void viewReturnsTranscriptWithNullReply() throws Exception {
    String id = idOf(start("john"));

    mvc.perform(get("/api/conversations/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conversation_id").value(id))
        .andExpect(jsonPath("$.reply").value(nullValue()))
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

  /** The two-day-pause proof: a conversation started by one process is readable by the next. */
  @Test
  void conversationSurvivesRestart(@TempDir Path dir) {
    String url = "jdbc:h2:file:" + dir.resolve("anna");
    String id;
    try (ConfigurableApplicationContext first = boot(url)) {
      id = first.getBean(ConversationService.class).start("jane").conversationId();
    }
    try (ConfigurableApplicationContext second = boot(url)) {
      int port = second.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
      HttpStatusCode status =
          RestClient.create()
              .get()
              .uri("http://localhost:{port}/api/conversations/{id}", port, id)
              .retrieve()
              .toBodilessEntity()
              .getStatusCode();

      assertThat(status.value()).isEqualTo(200);
    }
  }

  private static ConfigurableApplicationContext boot(String datasourceUrl) {
    return new SpringApplicationBuilder(AnnaApplication.class)
        .run("--spring.datasource.url=" + datasourceUrl, "--server.port=0");
  }

  private ResultActions start(String leadId) throws Exception {
    return send("{\"lead_id\":\"" + leadId + "\"}", MediaType.APPLICATION_JSON);
  }

  private ResultActions send(String body, MediaType contentType) throws Exception {
    return mvc.perform(post("/api/conversations").contentType(contentType).content(body));
  }

  private static String idOf(ResultActions started) throws Exception {
    return JsonPath.read(
        started.andReturn().getResponse().getContentAsString(), "$.conversation_id");
  }
}
