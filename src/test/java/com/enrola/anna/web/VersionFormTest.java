package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptVersion;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** The form's texts by file, its line ends, and the note the store gets. */
class VersionFormTest {

  static final PromptVersion ROW =
      new PromptVersion(
          "v3",
          Instant.EPOCH,
          PromptVersion.CONSOLE,
          "note",
          false,
          "the prompt",
          "the pack",
          "the schema",
          "the tools",
          "the templates");

  @ParameterizedTest
  @CsvSource({
    "PROMPT, the prompt",
    "PACK, the pack",
    "SCHEMA, the schema",
    "TOOLS, the tools",
    "TEMPLATES, the templates"
  })
  void eachFileReadsItsOwnArtifact(PromptFile file, String text) {
    VersionForm form = VersionForm.of(ROW, file);

    assertThat(form.text(file)).isEqualTo(text);
    assertThat(form).extracting(VersionForm::file, VersionForm::base).containsExactly(file, "v3");
  }

  @ParameterizedTest
  @EnumSource(PromptFile.class)
  void aMissingFieldIsEmptyText(PromptFile file) {
    assertThat(new VersionForm(null, null, null, null, null, null, null, null).text(file))
        .isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "'  wording  ' | wording",
        "''            | edited in console",
        "'   '         | edited in console"
      })
  void theNoteIsStrippedAndDefaulted(String typed, String stored) {
    assertThat(form("a\nb", typed).toSaveRequest().note()).isEqualTo(stored);
  }

  @Test
  void aLongNoteIsCutToTheColumnAndSaysSo() {
    VersionForm form = form("", "x".repeat(250));

    assertThat(form.toSaveRequest().note()).hasSize(VersionForm.NOTE_MAX);
    assertThat(form.noteCut()).isTrue();
    assertThat(form("", "x".repeat(VersionForm.NOTE_MAX)).noteCut()).isFalse();
  }

  @Test
  void aCutNoteNeverSplitsASurrogatePair() {
    String note = "x".repeat(VersionForm.NOTE_MAX - 1) + "😀";

    assertThat(form("", note).toSaveRequest().note())
        .isEqualTo("x".repeat(VersionForm.NOTE_MAX - 1));
  }

  @Test
  void browserLineEndsBecomeTheEditorsAgain() {
    PromptStore.SaveRequest request = form("one\r\ntwo\r\n", "").toSaveRequest();

    assertThat(request.promptMd()).isEqualTo("one\ntwo\n");
    assertThat(request.templatesJson()).isEqualTo("{\n}");
  }

  private static VersionForm form(String prompt, String note) {
    return new VersionForm(prompt, "pack", "schema", "tools", "{\r\n}", note, null, null);
  }
}
