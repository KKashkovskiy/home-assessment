package com.enrola.anna.web;

import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptVersion;
import java.util.Objects;

/**
 * The Configuration page's form: the five texts of a prompt version, a note for the save, the file
 * the editor shows and the version the texts started from. A save posts all five, changed or not,
 * so every version is whole. The text components are named by {@link PromptFile#field}; a missing
 * one binds as empty text, which the store's validation then refuses. Browsers post a textarea's
 * line breaks as CRLF, so they are turned back into the editor's LF and an unchanged file saves
 * unchanged.
 */
public record VersionForm(
    String prompt,
    String pack,
    String schema,
    String tools,
    String templates,
    String note,
    PromptFile file,
    String base) {

  /** The width of {@code prompt_version.note}. */
  static final int NOTE_MAX = 200;

  static final String DEFAULT_NOTE = "edited in console";

  public VersionForm {
    prompt = asTyped(prompt);
    pack = asTyped(pack);
    schema = asTyped(schema);
    tools = asTyped(tools);
    templates = asTyped(templates);
    note = Objects.requireNonNullElse(note, "");
    file = Objects.requireNonNullElse(file, PromptFile.valueOf(PromptFile.DEFAULT));
    base = Objects.requireNonNullElse(base, "");
  }

  /** {@code version}'s texts as the editor opens them on {@code file}, with no note yet. */
  static VersionForm of(PromptVersion version, PromptFile file) {
    return new VersionForm(
        PromptFile.PROMPT.read(version),
        PromptFile.PACK.read(version),
        PromptFile.SCHEMA.read(version),
        PromptFile.TOOLS.read(version),
        PromptFile.TEMPLATES.read(version),
        "",
        file,
        version.id());
  }

  /** The text of one artifact as typed. */
  public String text(PromptFile artifact) {
    return switch (artifact) {
      case PROMPT -> prompt;
      case PACK -> pack;
      case SCHEMA -> schema;
      case TOOLS -> tools;
      case TEMPLATES -> templates;
    };
  }

  /** The store's request; a blank note becomes the default and a long one is cut to the column. */
  PromptStore.SaveRequest toSaveRequest() {
    return new PromptStore.SaveRequest(prompt, pack, schema, tools, templates, storedNote());
  }

  /** The note is longer than the column, so the save keeps only its start. */
  boolean noteCut() {
    return note.strip().length() > NOTE_MAX;
  }

  private static String asTyped(String posted) {
    return Objects.requireNonNullElse(posted, "").replace("\r\n", "\n");
  }

  private String storedNote() {
    String stripped = note.strip();
    if (stripped.isEmpty()) {
      return DEFAULT_NOTE;
    }
    if (!noteCut()) {
      return stripped;
    }
    boolean splitsAPair = Character.isHighSurrogate(stripped.charAt(NOTE_MAX - 1));
    return stripped.substring(0, splitsAPair ? NOTE_MAX - 1 : NOTE_MAX);
  }
}
