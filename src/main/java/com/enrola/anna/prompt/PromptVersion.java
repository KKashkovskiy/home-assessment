package com.enrola.anna.prompt;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One prompt version: the five artifacts that travel together, and who made it when. Insert-only,
 * so {@link #isNew()} is always true: {@code save} always inserts and a repeated id fails on the
 * primary key, so content is never overwritten. Only {@code active} changes, through {@link
 * PromptVersionRepository#activate}. The JSON columns are parsed here and nowhere else.
 */
public record PromptVersion(
    @Id String id,
    Instant createdAt,
    String createdBy,
    String note,
    boolean active,
    String promptMd,
    String knowledgeMd,
    String schemaJson,
    String toolsJson,
    String templatesJson)
    implements Persistable<String> {

  /** {@code createdBy} of a row seeded from {@code prompts/vN/} in the repo. */
  public static final String REPO = "repo";

  /** {@code createdBy} of a row saved through {@link PromptStore#save}. */
  public static final String CONSOLE = "console";

  private static final Pattern ID = Pattern.compile("v([1-9]\\d*)");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<Map<String, String>> TEXTS = new TypeReference<>() {};

  /**
   * The N of an id {@code vN}, so v10 sorts after v2. Any other id is refused: the next id is one
   * past the highest N.
   */
  static int number(String id) {
    Matcher matched = ID.matcher(id);
    if (!matched.matches()) {
      throw new IllegalArgumentException("prompt version id must be vN, was " + id);
    }
    return Integer.parseInt(matched.group(1));
  }

  /** {@code texts} as the {@code templates_json} column stores them. */
  static String templatesJsonOf(Map<String, String> texts) {
    return JSON.writeValueAsString(texts);
  }

  /**
   * The JSON columns, each parsed once; empty where a column is not JSON, or for the templates not
   * a JSON object. A template name may map to null when the JSON says so.
   */
  record Columns(
      Optional<JsonNode> schema,
      Optional<JsonNode> tools,
      Optional<Map<String, String>> templates) {}

  Columns parse() {
    return new Columns(
        parsed(() -> JSON.readTree(schemaJson)),
        parsed(() -> JSON.readTree(toolsJson)),
        parsed(() -> JSON.readValue(templatesJson, TEXTS)));
  }

  /** {@code read}'s value; empty when it throws or, for a JSON {@code null}, returns null. */
  private static <T> Optional<T> parsed(Supplier<T> read) {
    try {
      return Optional.ofNullable(read.get());
    } catch (JacksonException e) {
      return Optional.empty();
    }
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return true;
  }
}
