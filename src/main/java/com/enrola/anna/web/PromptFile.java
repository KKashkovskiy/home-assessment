package com.enrola.anna.web;

import com.enrola.anna.prompt.PromptVersion;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The five artifacts of a prompt version as the Configuration page offers them in its File select.
 * The constant is the {@code file} parameter; {@link #field} names the artifact's textarea and its
 * {@link VersionForm} component; {@code language} is what Monaco highlights it as.
 */
public enum PromptFile {
  PROMPT("System prompt", "markdown"),
  PACK("Knowledge pack", "markdown"),
  SCHEMA("Decision schema", "json"),
  TOOLS("Tools", "json"),
  TEMPLATES("Templates", "json");

  /**
   * The file the editor opens on when a request names none; a name, so parameters default to it.
   */
  public static final String DEFAULT = "PROMPT";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String label;
  private final String language;

  PromptFile(String label, String language) {
    this.label = label;
    this.language = language;
  }

  public String label() {
    return label;
  }

  public String language() {
    return language;
  }

  /** "prompt" for PROMPT: the form field the page posts this artifact's text in. */
  public String field() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * This artifact's text in {@code version} as the editor shows it. The templates are stored as one
   * line of JSON, so an object is shown one template per line, which also makes two versions with
   * the same templates diff as equal; text that is not a JSON object is shown as stored, so a
   * broken row can still be repaired.
   */
  public String read(PromptVersion version) {
    return switch (this) {
      case PROMPT -> version.promptMd();
      case PACK -> version.knowledgeMd();
      case SCHEMA -> version.schemaJson();
      case TOOLS -> version.toolsJson();
      case TEMPLATES -> onePerLine(version.templatesJson());
    };
  }

  private static String onePerLine(String templatesJson) {
    try {
      JsonNode templates = JSON.readTree(templatesJson);
      return templates.isObject()
          ? JSON.writerWithDefaultPrettyPrinter().writeValueAsString(templates)
          : templatesJson;
    } catch (JacksonException e) {
      return templatesJson;
    }
  }
}
