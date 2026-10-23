package com.enrola.anna.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Save and activate against the real table, in a database the context migrated and seeded. */
@SpringBootTest(properties = "OPENAI_API_KEY=")
class PromptStoreTest {

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void databaseInTempDir(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("anna"));
  }

  @Autowired PromptStore store;
  @Autowired PromptVersionRepository versions;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void onlyTheSeededV1() {
    jdbc.update("delete from prompt_version where id <> 'v1'");
    versions.activate("v1");
  }

  @Test
  void saveInsertsNextIdInactive() {
    PromptStore.SaveResult result = store.save(copyOfV1("test"));

    assertThat(result).isEqualTo(new PromptStore.SaveResult.Saved("v2", List.of()));
    assertThat(store.list())
        .extracting(PromptStore.VersionSummary::id, PromptStore.VersionSummary::active)
        .containsExactly(tuple("v1", true), tuple("v2", false));
    assertThat(versions.findById("v2").orElseThrow())
        .extracting(PromptVersion::createdBy, PromptVersion::note)
        .containsExactly("console", "test");
  }

  @Test
  void saveRejectsInvalidWithoutInsert() {
    PromptVersion v1 = versions.findById("v1").orElseThrow();
    PromptStore.SaveRequest missingKnowledge =
        new PromptStore.SaveRequest(
            v1.promptMd().replace("{{knowledge}}", ""),
            v1.knowledgeMd(),
            v1.schemaJson(),
            v1.toolsJson(),
            v1.templatesJson(),
            "bad");

    assertThat(store.save(missingKnowledge))
        .isEqualTo(
            new PromptStore.SaveResult.Rejected(
                List.of("prompt is missing placeholder {{knowledge}}"), List.of()));
    assertThat(store.list()).hasSize(1);
  }

  /** A deleted row must not hand its id out again while a higher one exists. */
  @Test
  void nextIdIsOnePastTheHighest() {
    store.save(copyOfV1("second"));
    store.save(copyOfV1("third"));
    jdbc.update("delete from prompt_version where id = 'v2'");

    assertThat(store.save(copyOfV1("fourth")))
        .isEqualTo(new PromptStore.SaveResult.Saved("v4", List.of()));
  }

  @Test
  void activateFlipsExactlyOne() {
    store.save(copyOfV1("second"));

    store.activate("v2");

    assertThat(store.activeId()).isEqualTo("v2");
    assertThat(store.list())
        .extracting(PromptStore.VersionSummary::active)
        .containsExactly(false, true);
  }

  @Test
  void activateUnknownThrowsAndKeepsActive() {
    assertThatThrownBy(() -> store.activate("v9")).isInstanceOf(PromptVersionMissing.class);
    assertThat(store.activeId()).isEqualTo("v1");
  }

  /** Damage only direct SQL can do; the active version stays and turns on v2 cannot load it. */
  @Test
  void activateCorruptThrowsAndKeepsActive() {
    store.save(copyOfV1("second"));
    jdbc.update("update prompt_version set tools_json = '{' where id = 'v2'");

    assertThatThrownBy(() -> store.activate("v2"))
        .isInstanceOf(PromptVersionCorrupt.class)
        .hasMessage("prompt version v2 cannot be loaded: tools are not valid JSON");
    assertThat(store.activeId()).isEqualTo("v1");
  }

  @Test
  void contentColumnsAreNeverUpdated() {
    PromptVersion before = versions.findById("v1").orElseThrow();

    store.save(copyOfV1("same as v1"));

    assertThat(versions.findById("v1").orElseThrow()).isEqualTo(before);
    assertThat(
            Stream.of(PromptVersionRepository.class.getMethods())
                .filter(method -> method.isAnnotationPresent(Modifying.class))
                .map(Method::getName))
        .containsExactly("activate");
  }

  /** v1's content again: a valid save. */
  private PromptStore.SaveRequest copyOfV1(String note) {
    PromptVersion v1 = versions.findById("v1").orElseThrow();
    return new PromptStore.SaveRequest(
        v1.promptMd(), v1.knowledgeMd(), v1.schemaJson(), v1.toolsJson(), v1.templatesJson(), note);
  }
}
