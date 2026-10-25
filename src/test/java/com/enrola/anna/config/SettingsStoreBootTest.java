package com.enrola.anna.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A boot over a fresh database and a settings file whose persona the active prompt version cannot
 * render. The store checks its overrides only once the versions are seeded. Checked earlier, the
 * boot would fail, as a fresh database has no active version; never checked, the overrides would
 * run.
 */
@SpringBootTest(properties = "OPENAI_API_KEY=")
class SettingsStoreBootTest {

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void dataInTempDir(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("anna"));
    registry.add("anna.data-dir", tempDir::toString);
  }

  /** Written before the context boots, so the store loads it. */
  @BeforeAll
  static void overridesWithALongerCompany() {
    new SettingsStore(SettingsFixture.DEFAULTS, tempDir, List.of(), List.of())
        .save(
            SettingsFixture.with(
                SettingsFixture.DEFAULTS, "persona", "company", "Comparato Health Insurance"));
  }

  @Autowired SettingsStore settings;

  @Test
  void overridesTheActiveVersionCannotLoadAreSetAside() {
    assertThat(settings.current()).isEqualTo(SettingsFixture.DEFAULTS);
    assertThat(tempDir.resolve("settings.json.bad")).exists();
    assertThat(tempDir.resolve("settings.json")).doesNotExist();
  }
}
