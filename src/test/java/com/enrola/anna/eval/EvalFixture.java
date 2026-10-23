package com.enrola.anna.eval;

import com.enrola.anna.config.SettingsDefaults;
import com.enrola.anna.config.SettingsStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The harness offline over real H2: the stand-in model and moderation, no key. Every subclass
 * shares one context, database and data directory for the run, as the cache keeps the context
 * across classes; each test starts with no conversations, only the seeded leads, and the yml's
 * settings.
 */
@SpringBootTest(properties = {"OPENAI_API_KEY=", "eval.offline=true"})
@Import(EvalConfig.class)
abstract class EvalFixture {

  /** Outlives every test class, unlike a JUnit temp directory, as the context on it does. */
  static final Path DATA = temporaryDirectory();

  /** The context's database, which stands in for the app's too. */
  static final String APP_DB = "jdbc:h2:file:" + DATA.resolve("anna");

  @DynamicPropertySource
  static void dataInTempDir(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> APP_DB);
    registry.add("anna.data-dir", DATA::toString);
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("anna-eval-tests");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired SettingsStore settings;
  @Autowired SettingsDefaults defaults;

  @BeforeEach
  void noConversations() {
    if (!settings.current().equals(defaults.settings())) {
      settings.save(defaults.settings());
    }
    jdbc.update("delete from booking");
    jdbc.update("delete from trace_event");
    jdbc.update("delete from turn");
    jdbc.update("delete from message");
    jdbc.update("delete from conversation");
    jdbc.update("delete from suppression");
    jdbc.update("delete from lead where id not in ('lauren', 'john', 'jane')");
  }
}
