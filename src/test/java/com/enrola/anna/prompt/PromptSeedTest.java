package com.enrola.anna.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enrola.anna.AppClock;
import com.enrola.anna.config.SettingsDefaults;
import com.enrola.anna.config.SettingsFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
  @Autowired SettingsDefaults yml;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  /** Back to what a fresh database seeds: every repo version, the newest active. */
  @AfterEach
  void onlyTheSeededRepoVersions() {
    jdbc.update("delete from prompt_version where created_by <> 'repo'");
    jdbc.update("update prompt_version set active = false");
    seed.seed();
  }

  @Test
  void seedsEveryRepoVersionAndActivatesTheNewestOnEmptyDatabase() {
    assertThat(store.list())
        .extracting(PromptStore.VersionSummary::id)
        .isEqualTo(loader.availableVersions());
    assertThat(store.list())
        .allSatisfy(version -> assertThat(version.createdBy()).isEqualTo("repo"));
    assertThat(store.activeId()).isEqualTo(newestRepoVersion());
  }

  @Test
  void secondSeedDoesNotDuplicate() {
    int seeded = store.list().size();

    seed.seed();

    assertThat(store.list()).hasSize(seeded);
  }

  @Test
  void seededContentIsByteIdentical() throws IOException {
    for (String id : loader.availableVersions()) {
      PromptVersion seeded = versions.findById(id).orElseThrow();
      Map<String, String> templates = seeded.parse().templates().orElseThrow();

      assertThat(seeded.promptMd()).isEqualTo(repoFile(id, id + ".md"));
      assertThat(seeded.knowledgeMd()).isEqualTo(repoFile(id, "knowledge.md"));
      assertThat(seeded.schemaJson()).isEqualTo(repoFile(id, "agent-decision.schema.json"));
      assertThat(seeded.toolsJson()).isEqualTo(repoFile(id, "tools.json"));
      for (Template template : Template.values()) {
        assertThat(templates.get(template.name()))
            .isEqualTo(repoFile(id, "templates/" + template.name() + ".txt"));
      }
    }
  }

  /** An existing data/ keeps running what it ran: a version new to it arrives inactive. */
  @Test
  void seedKeepsTheActivatedVersionAndAddsANewRepoVersionInactive() {
    versions.activate("v1");
    jdbc.update("delete from prompt_version where id = ?", newestRepoVersion());

    seed.seed();

    assertThat(store.activeId()).isEqualTo("v1");
    assertThat(versions.findById(newestRepoVersion()))
        .hasValueSatisfying(newest -> assertThat(newest.active()).isFalse());
  }

  @Test
  void seedActivatesTheNewestRepoVersionNotASaveWhenNoneIsActive() {
    store.save(copyOfV1(), SettingsFixture.DEFAULTS.persona());
    jdbc.update("update prompt_version set active = false");

    seed.seed();

    assertThat(store.activeId()).isEqualTo(newestRepoVersion());
  }

  /**
   * A save made before the repo shipped a directory with the same id keeps the id; the seed skips
   * the repo's copy and activates the newest version it did seed.
   */
  @Test
  void seedNeverActivatesASaveHoldingARepoVersionsId() {
    List<String> repo = loader.availableVersions();
    String newest = repo.getLast();
    jdbc.update("delete from prompt_version where id = ?", newest);
    jdbc.update(
        "insert into prompt_version select ?, created_at, 'console', 'saved first', false,"
            + " prompt_md, knowledge_md, schema_json, tools_json, templates_json"
            + " from prompt_version where id = 'v1'",
        newest);
    jdbc.update("update prompt_version set active = false");

    seed.seed();

    assertThat(store.activeId()).isEqualTo(repo.get(repo.size() - 2));
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
            return Stream.concat(super.availableVersions().stream(), Stream.of("v99")).toList();
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
        .isThrownBy(() -> seedWith(repoWithCurlyQuoteV2, store, yml).seed())
        .withMessage(
            "prompts/v99 is invalid: errors [], warnings [FALLBACK has characters outside GSM-7]");
    assertThat(versions.existsById("v99")).isFalse();
  }

  /**
   * A yml persona can push the active version's openers over their cap: that fails the boot, as the
   * yml is the owner's to fix.
   */
  @Test
  void activeVersionTheYmlPersonaCannotLoadFailsTheSeed() {
    SettingsDefaults longerCompany =
        SettingsFixture.yml("anna.persona.company=Comparato Health Insurance");

    assertThatThrownBy(() -> seedWith(longerCompany).seed())
        .isInstanceOf(PromptVersionCorrupt.class)
        .hasMessage(
            "prompt version "
                + store.activeId()
                + " cannot be loaded: OPENER_PROVIDER renders to 322 chars, over 320");
  }

  /** A seed over the real table and loader, with {@code yml}'s persona. */
  private PromptSeed seedWith(SettingsDefaults yml) {
    return seedWith(loader, new PromptStore(versions, clock), yml);
  }

  private PromptSeed seedWith(PromptLoader repo, PromptStore table, SettingsDefaults yml) {
    return new PromptSeed(versions, repo, table, yml, clock, tx);
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

  private String newestRepoVersion() {
    return loader.availableVersions().getLast();
  }

  private static String repoFile(String id, String path) throws IOException {
    return new ClassPathResource("prompts/" + id + "/" + path)
        .getContentAsString(StandardCharsets.UTF_8);
  }
}
