package com.enrola.anna.eval;

import com.enrola.anna.eval.Scenario.Expect;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads {@code evals/scenarios/*.yaml}. Each file is validated against {@code
 * evals/schema/scenario.schema.json} before it is mapped, so a typo such as {@code reply_match}
 * fails loudly with the file's name; its id must equal its file name, and every regex must compile,
 * so no trial can crash on one.
 */
final class ScenarioLoader {

  static final Path SCENARIOS = Path.of("evals/scenarios");
  static final Path SCHEMA = Path.of("evals/schema/scenario.schema.json");

  /**
   * Seed counters left out of a file are zero, not a mapping error, and a clock keeps the offset it
   * was written with, so its date is the scenario's day.
   */
  private static final YAMLMapper YAML =
      YAMLMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
          .build();

  private final Schema schema;

  ScenarioLoader() {
    try (InputStream in = Files.newInputStream(SCHEMA)) {
      schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + SCHEMA, e);
    }
  }

  /** Every {@code *.yaml} in {@code dir}, in id order. A missing directory is an error. */
  List<Scenario> loadAll(Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .filter(file -> file.toString().endsWith(".yaml"))
          .map(this::load)
          .sorted(Comparator.comparing(Scenario::id))
          .toList();
    }
  }

  Scenario load(Path file) {
    JsonNode node = validated(file);
    Scenario scenario =
        switch (node.path("kind").stringValue()) {
          case "turn" -> YAML.treeToValue(node, Scenario.Turn.class);
          case "story" -> YAML.treeToValue(node, Scenario.Story.class);
          default -> throw new ScenarioException(file, "unknown kind"); // the schema rejects it
        };
    compileRegexes(file, scenario.expect());
    return scenario;
  }

  private JsonNode validated(Path file) {
    JsonNode node = read(file);
    List<Error> errors = schema.validate(node);
    if (!errors.isEmpty()) {
      throw new ScenarioException(
          file, errors.stream().map(Error::getMessage).collect(Collectors.joining("; ")));
    }
    String stem = file.getFileName().toString().replace(".yaml", "");
    if (!stem.equals(node.path("id").stringValue())) {
      throw new ScenarioException(file, "id must equal the file name");
    }
    return node;
  }

  private static void compileRegexes(Path file, Expect expect) {
    compile(file, "reply_matches", expect.replyMatches());
    compile(file, "reply_not_matches", expect.replyNotMatches());
    compile(file, "transcript_matches", expect.transcriptMatches());
    if (expect.everyReply() != null) {
      compile(file, "every_reply.reply_not_matches", expect.everyReply().replyNotMatches());
    }
  }

  private static void compile(Path file, String field, List<String> regexes) {
    for (String regex : regexes == null ? List.<String>of() : regexes) {
      try {
        Pattern.compile(regex);
      } catch (PatternSyntaxException e) {
        throw new ScenarioException(file, field + " has a bad regex: " + e.getDescription());
      }
    }
  }

  private static JsonNode read(Path file) {
    try {
      return YAML.readTree(Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }
}
