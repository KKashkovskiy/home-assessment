package com.enrola.anna.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enrola.anna.AppClock;
import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.config.SettingsStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** The boot-time seed against the real table, in a database the context migrated and seeded. */
@SpringBootTest(properties = "OPENAI_API_KEY=")
class PromptSeedTest {

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void dataInTempDir(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("anna"));
    registry.add("anna.data-dir", tempDir::toString);
  }

  @Autowired PromptSeed seed;
  @Autowired PromptStore store;
  @Autowired PromptVersionRepository versions;
  @Autowired PromptLoader loader;
  @Autowired AppClock clock;
  @Autowired SettingsStore settings;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void onlyTheSeededV1() {
    jdbc.update("delete from prompt_version where id <> 'v1'");
    versions.activate("v1");
  }

  @Test
  void seedsV1ActiveOnEmptyDatabase() {
    assertThat(store.list())
        .singleElement()
        .satisfies(
            v1 -> {
              assertThat(v1.id()).isEqualTo("v1");
              assertThat(v1.active()).isTrue();
              assertThat(v1.createdBy()).isEqualTo("repo");
            });
  }

  @Test
  void secondSeedDoesNotDuplicate() {
    seed.seed();

    assertThat(store.list()).hasSize(1);
  }

  @Test
  void seededContentIsByteIdentical() throws IOException {
    PromptVersion v1 = versions.findById("v1").orElseThrow();
    Map<String, String> templates = v1.parse().templates().orElseThrow();

    assertThat(v1.promptMd()).isEqualTo(repoFile("v1.md"));
    assertThat(v1.knowledgeMd()).isEqualTo(repoFile("knowledge.md"));
    assertThat(v1.schemaJson()).isEqualTo(repoFile("agent-decision.schema.json"));
    assertThat(v1.toolsJson()).isEqualTo(repoFile("tools.json"));
    for (Template template : Template.values()) {
      assertThat(templates.get(template.name()))
          .isEqualTo(repoFile("templates/" + template.name() + ".txt"));
    }
  }

  @Test
  void seedKeepsTheActivatedVersion() {
    store.save(copyOfV1());
    store.activate("v2");

    seed.seed();

    assertThat(store.activeId()).isEqualTo("v2");
  }

  @Test
  void seedActivatesTheLowestWhenNoneIsActive() {
    store.save(copyOfV1());
    jdbc.update("update prompt_version set active = false");

    seed.seed();

    assertThat(store.activeId()).isEqualTo("v1");
  }

  /**
   * The repo is the owner's: a directory with any finding, a warning a save would let through
   * included, stops the boot instead of seeding.
   */
  @Test
  void repoVersionWithAWarningFailsTheSeed() {
    PromptLoader repoWithCurlyQuoteV2 =
        new PromptLoader(new DefaultResourceLoader()) {
          @Override
          public List<String> availableVersions() {
            return List.of("v1", "v2");
          }

          @Override
          public PromptVersion load(String id, Instant now) {
            PromptVersion v1 = super.load("v1", now);
            Map<String, String> templates = new HashMap<>(v1.parse().templates().orElseThrow());
            templates.put(Template.FALLBACK.name(), "Sorry, I didn’t get that.");
            return new PromptVersion(
                id,
                now,
                PromptVersion.REPO,
                null,
                false,
                v1.promptMd(),
                v1.knowledgeMd(),
                v1.schemaJson(),
                v1.toolsJson(),
                PromptVersion.templatesJsonOf(templates));
          }
        };

    assertThatIllegalStateException()
        .isThrownBy(() -> seedWith(repoWithCurlyQuoteV2, store, settings).seed())
        .withMessage(
            "prompts/v2 is invalid: errors [], warnings [FALLBACK has characters outside GSM-7]");
    assertThat(store.list()).hasSize(1);
  }

  /**
   * A yml persona can push the active version's openers over their cap: that fails the boot, as the
   * yml is the owner's to fix.
   */
  @Test
  void activeVersionTheYmlPersonaCannotLoadFailsTheSeed(@TempDir Path dataDir) {
    SettingsStore longerCompany = new SettingsStore(withLongerCompany(), dataDir);

    assertThatThrownBy(() -> seedWith(longerCompany).seed())
        .isInstanceOf(PromptVersionCorrupt.class)
        .hasMessage(
            "prompt version v1 cannot be loaded: OPENER_PROVIDER renders to 322 chars, over 320");
  }

  /**
   * A settings file whose persona the active version cannot render never stops the app: it is set
   * aside and the yml's settings run.
   */
  @Test
  void overridesTheActiveVersionCannotLoadAreSetAside(@TempDir Path dataDir) {
    new SettingsStore(SettingsFixture.DEFAULTS, dataDir).save(withLongerCompany());
    SettingsStore fromFile = new SettingsStore(SettingsFixture.DEFAULTS, dataDir);
    assertThat(fromFile.current()).isEqualTo(withLongerCompany());

    seedWith(fromFile).seed();

    assertThat(fromFile.current()).isEqualTo(SettingsFixture.DEFAULTS);
    assertThat(dataDir.resolve("settings.json.bad")).exists();
    assertThat(dataDir.resolve("settings.json")).doesNotExist();
  }

  private static Settings withLongerCompany() {
    return SettingsFixture.with(
        SettingsFixture.DEFAULTS, "persona", "company", "Comparato Health Insurance");
  }

  /** A seed over the real table and loader, with the prompt store's check on {@code settings}. */
  private PromptSeed seedWith(SettingsStore settings) {
    return seedWith(loader, new PromptStore(versions, settings, clock), settings);
  }

  private PromptSeed seedWith(PromptLoader repo, PromptStore table, SettingsStore settings) {
    return new PromptSeed(versions, repo, table, settings, clock, tx);
  }

  private PromptStore.SaveRequest copyOfV1() {
    PromptVersion v1 = versions.findById("v1").orElseThrow();
    return new PromptStore.SaveRequest(
        v1.promptMd(),
        v1.knowledgeMd(),
        v1.schemaJson(),
        v1.toolsJson(),
        v1.templatesJson(),
        "copy");
  }

  private static String repoFile(String path) throws IOException {
    return new ClassPathResource("prompts/v1/" + path).getContentAsString(StandardCharsets.UTF_8);
  }
}
