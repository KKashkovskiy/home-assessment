package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.prompt.PromptVersion;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Each file reads its own column; the templates object reads one template per line. */
class PromptFileTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  @ParameterizedTest
  @CsvSource({
    "PROMPT, the prompt, markdown",
    "PACK, the pack, markdown",
    "SCHEMA, the schema, json",
    "TOOLS, the tools, json"
  })
  void eachFileReadsItsOwnColumn(PromptFile file, String text, String language) {
    assertThat(file.read(row("{}"))).isEqualTo(text);
    assertThat(file.language()).isEqualTo(language);
  }

  @Test
  void aTemplatesObjectReadsOneTemplatePerLine() {
    String stored = "{\"A\":\"one\",\"B\":\"two\"}";

    String shown = PromptFile.TEMPLATES.read(row(stored));

    assertThat(shown.lines()).hasSize(4);
    assertThat(JSON.readTree(shown)).isEqualTo(JSON.readTree(stored));
  }

  /** A row broken by hand still opens, so it can be repaired and saved as a new version. */
  @ParameterizedTest
  @ValueSource(strings = {"{", "[\"A\"]", ""})
  void templatesThatAreNotAnObjectReadAsStored(String stored) {
    assertThat(PromptFile.TEMPLATES.read(row(stored))).isEqualTo(stored);
  }

  private static PromptVersion row(String templatesJson) {
    return new PromptVersion(
        "v1",
        Instant.EPOCH,
        "repo",
        null,
        true,
        "the prompt",
        "the pack",
        "the schema",
        "the tools",
        templatesJson);
  }
}
