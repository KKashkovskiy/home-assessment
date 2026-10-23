package com.enrola.anna.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads every YAML file under {@code evals/rubrics/} and fails loudly on a malformed one, so a bad
 * file never silently drops a question: its name must be {@code J-<ID>.yaml}, its id must equal
 * that name, its question must end with a question mark, and it needs a scope, a severity, at least
 * one tag and at least one pass and one fail example. The Evals page checks a rubric it saves with
 * the same rules.
 */
public final class RubricLoader {

  static final Path RUBRICS = Path.of("evals/rubrics");

  /** YAML spells the enums lowercase and the fields snake_case; a misspelt field is an error. */
  private static final YAMLMapper YAML =
      YAMLMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private RubricLoader() {}

  /** Every rubric in {@code dir}, in id order. */
  static List<Rubric> load(Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .filter(file -> file.getFileName().toString().matches(".*\\.ya?ml"))
          .sorted()
          .map(RubricLoader::one)
          .toList();
    }
  }

  /** Why {@code yaml} would not load as the rubric file {@code fileName}; empty when it would. */
  public static Optional<String> problem(String fileName, String yaml) {
    try {
      parse(fileName, yaml);
      return Optional.empty();
    } catch (IllegalStateException e) {
      return Optional.of(e.getMessage());
    }
  }

  private static Rubric one(Path file) {
    try {
      return parse(file.getFileName().toString(), Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  private static Rubric parse(String name, String yaml) {
    if (!name.matches("J-[A-Z-]+\\.ya?ml")) {
      throw new IllegalStateException(name + ": a rubric file is named J-<ID>.yaml");
    }
    Rubric rubric = read(name, yaml);
    String stem = name.replaceAll("\\.ya?ml$", "");
    if (!stem.equals(rubric.id())) {
      throw new IllegalStateException(name + ": id " + rubric.id() + " != file name");
    }
    if (rubric.question() == null || !rubric.question().strip().endsWith("?")) {
      throw new IllegalStateException(rubric.id() + ": question must end with ?");
    }
    require(rubric.scope() != null, rubric, "needs a scope, turn or transcript");
    require(rubric.severity() != null, rubric, "needs a severity, hard or soft");
    require(!rubric.appliesTo().isEmpty(), rubric, "needs at least one applies_to tag");
    require(
        !rubric.examples().pass().isEmpty() && !rubric.examples().fail().isEmpty(),
        rubric,
        "needs at least one pass and one fail example");
    return rubric;
  }

  private static void require(boolean holds, Rubric rubric, String rule) {
    if (!holds) {
      throw new IllegalStateException(rubric.id() + ": " + rule);
    }
  }

  private static Rubric read(String name, String yaml) {
    try {
      Rubric rubric = YAML.readValue(yaml, Rubric.class);
      if (rubric == null) {
        throw new IllegalStateException(name + ": the file is empty");
      }
      return rubric;
    } catch (JacksonException e) {
      throw new IllegalStateException(name + ": " + e.getOriginalMessage(), e);
    }
  }
}
