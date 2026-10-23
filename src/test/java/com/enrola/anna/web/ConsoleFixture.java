package com.enrola.anna.web;

import com.enrola.anna.AppClock;
import com.enrola.anna.conversation.ConversationService;
import com.enrola.anna.conversation.Source;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The console over real H2 on Sunday 26 July 2026, 10:00 in Melbourne, with locks that give up
 * after 100 ms and, unless a subclass scripts one, no model. Every test starts with no
 * conversations and only the seeded leads; conversations are made through the service, never by
 * inserting rows.
 */
@SpringBootTest(
    properties = {
      "OPENAI_API_KEY=",
      "anna.turns.lock-wait=100ms",
      "anna.clock.mode=FIXED",
      "anna.clock.fixed-at=" + ConsoleFixture.NOW
    })
abstract class ConsoleFixture {

  static final String NOW = "2026-07-26T00:00:00Z";

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void databaseInTempDir(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("anna"));
  }

  @Autowired WebApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  @Autowired ConversationService conversations;
  @Autowired AppClock clock;
  MockMvc mvc;

  @BeforeEach
  void noConversations() {
    clock.fixAt(Instant.parse(NOW));
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    jdbc.update("delete from booking");
    jdbc.update("delete from trace_event");
    jdbc.update("delete from turn");
    jdbc.update("delete from message");
    jdbc.update("delete from conversation");
    jdbc.update("delete from suppression");
    jdbc.update("delete from lead where id not in ('lauren', 'john', 'jane')");
  }

  /** A conversation started from the console, as its start panel does. */
  String start(String leadId) {
    return conversations.start(leadId, Source.SIM).conversationId();
  }

  int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  static String body(ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString();
  }
}
