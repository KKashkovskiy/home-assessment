package com.enrola.anna.web;

import com.enrola.anna.AppClock;
import com.enrola.anna.agent.ScriptedModelClient;
import com.enrola.anna.config.SettingsDefaults;
import com.enrola.anna.config.SettingsStore;
import com.enrola.anna.conversation.ConversationService;
import com.enrola.anna.conversation.Source;
import com.enrola.anna.guard.ScriptedModerationClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The console over real H2 on Sunday 26 July 2026, 10:00 in Melbourne, with locks that give up
 * after 100 ms and no model, unless a subclass imports {@link Scripted}. Every test starts with no
 * conversations, only the seeded leads, prompt v1 active, the yml's settings and the shipped quick
 * replies; conversations are made through the service, never by inserting rows. Every context
 * shares one database, data directory and copy of the eval files for the run, since the cache keeps
 * contexts across classes; each test resets the database and the data directory first.
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

  /** Outlives every test class, unlike a JUnit temp directory, as the contexts on it do. */
  static final Path DATABASE = temporaryDirectory();

  /** The repo's eval files and deny-list, copied in the repo's layout, for the Evals page. */
  static final Path EVALS = copyOfTheEvalFiles();

  @DynamicPropertySource
  static void dataInTempDir(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + DATABASE.resolve("anna"));
    registry.add("anna.data-dir", DATABASE::toString);
    registry.add("anna.evals-dir", EVALS::toString);
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("anna-console-tests");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path copyOfTheEvalFiles() {
    Path evals = DATABASE.resolve("evals");
    try {
      for (String dir : List.of("schema", "scenarios", "rubrics", "prompts")) {
        copyDirectory(Path.of("evals", dir), evals.resolve(dir));
      }
      Files.createDirectories(evals.resolve("runs"));
      copyDirectory(
          Path.of("src/main/resources/guards"), DATABASE.resolve("src/main/resources/guards"));
      return evals;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The files directly in {@code from}. */
  static void copyDirectory(Path from, Path to) throws IOException {
    Files.createDirectories(to);
    try (Stream<Path> files = Files.list(from)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        Files.copy(file, to.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  /** The model and moderation scripted; every subclass that imports it shares one context. */
  @TestConfiguration
  static class Scripted {
    @Bean
    ScriptedModelClient scriptedModel() {
      return new ScriptedModelClient();
    }

    @Bean
    ScriptedModerationClient scriptedModeration() {
      return new ScriptedModerationClient();
    }
  }

  @Autowired WebApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  @Autowired ConversationService conversations;
  @Autowired AppClock clock;
  @Autowired SettingsStore settings;
  @Autowired SettingsDefaults defaults;
  MockMvc mvc;

  @BeforeEach
  void noConversations() throws IOException {
    if (!settings.current().equals(defaults.settings())) {
      settings.save(defaults.settings());
    }
    Files.deleteIfExists(DATABASE.resolve("quick-replies.txt"));
    clock.fixAt(Instant.parse(NOW));
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    jdbc.update("delete from eval_run");
    jdbc.update("delete from booking");
    jdbc.update("delete from trace_event");
    jdbc.update("delete from turn");
    jdbc.update("delete from message");
    jdbc.update("delete from conversation");
    jdbc.update("delete from suppression");
    jdbc.update("delete from lead where id not in ('lauren', 'john', 'jane')");
    jdbc.update("delete from prompt_version where id <> 'v1'");
    jdbc.update("update prompt_version set active = (id = 'v1')");
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
