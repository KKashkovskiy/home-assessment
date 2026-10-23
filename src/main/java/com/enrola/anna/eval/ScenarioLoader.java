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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads and validates {@code evals/scenarios/*.yaml}, for the harness and for the Evals page's save
 * alike. A text is validated against {@code evals/schema/scenario.schema.json} before it is mapped,
 * so a typo such as {@code reply_match} fails loudly; its id must equal its file name, every regex
 * must compile and every {@code stop_when} entry must parse, so no trial can crash on one.
 */
public final class ScenarioLoader {

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

  /** What a scenario's text came to: the scenario, or every problem found in it. */
  public sealed interface Validated permits Valid, Invalid {}

  /** The text maps to {@code scenario}. */
  public record Valid(Scenario scenario) implements Validated {}

  /** The text does not load, for each of {@code problems}. */
  public record Invalid(List<String> problems) implements Validated {

    public Invalid {
      problems = List.copyOf(problems);
    }
  }

  private final Schema schema;

  /** The harness's loader, on the schema under the working directory. */
  ScenarioLoader() {
    this(SCHEMA);
  }

  public ScenarioLoader(Path schemaFile) {
    try (InputStream in = Files.newInputStream(schemaFile)) {
      schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + schemaFile, e);
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

  /** The file's scenario, or a {@link ScenarioException} naming the file and every problem. */
  Scenario load(Path file) {
    String id = file.getFileName().toString().replace(".yaml", "");
    return switch (validate(read(file), id)) {
      case Valid valid -> valid.scenario();
      case Invalid invalid ->
          throw new ScenarioException(file, String.join("; ", invalid.problems()));
    };
  }

  /**
   * {@code yaml} checked as the file {@code <id>.yaml}: it parses, fits the schema, carries that
   * id, maps, and its regexes and stop conditions compile. A text that breaks the schema is not
   * mapped, since mapping would only repeat the schema's problems.
   */
  public Validated validate(String yaml, String id) {
    JsonNode node;
    try {
      node = YAML.readTree(yaml);
    } catch (JacksonException e) {
      return new Invalid(List.of("not YAML: " + e.getOriginalMessage()));
    }
    if (node == null || !node.isObject()) {
      return new Invalid(List.of("not a scenario: the file must be a YAML mapping"));
    }
    List<String> problems = new ArrayList<>();
    schema.validate(node).stream().map(ScenarioLoader::located).forEach(problems::add);
    if (!id.equals(node.path("id").stringValue())) {
      problems.add("id must equal the file name (" + id + ")");
    }
    if (!problems.isEmpty()) {
      return new Invalid(problems);
    }
    return mapped(node);
  }

  /**
   * {@code expect.stage: does not have a value in the enumeration [...]}: the YAML path, as the
   * author types it, down to the property a problem names, then what is wrong; a problem with the
   * whole file has no path.
   */
  private static String located(Error error) {
    String path = error.getInstanceLocation().toString().replaceFirst("^/", "").replace('/', '.');
    if (error.getProperty() != null) {
      path = path.isEmpty() ? error.getProperty() : path + "." + error.getProperty();
    }
    return path.isEmpty() ? error.getMessage() : path + ": " + error.getMessage();
  }

  private static Validated mapped(JsonNode node) {
    Scenario scenario;
    try {
      scenario =
          switch (node.path("kind").stringValue()) {
            case "turn" -> YAML.treeToValue(node, Scenario.Turn.class);
            case "story" -> YAML.treeToValue(node, Scenario.Story.class);
            default -> throw new IllegalStateException("unknown kind"); // the schema rejects it
          };
    } catch (JacksonException e) {
      return new Invalid(List.of("cannot read: " + e.getOriginalMessage()));
    }
    List<String> problems = new ArrayList<>(regexProblems(scenario.expect()));
    problems.addAll(regexProblems("config.forbidden", scenario.config().forbidden()));
    if (scenario instanceof Scenario.Story story) {
      problems.addAll(stopWhenProblems(story));
    }
    return problems.isEmpty() ? new Valid(scenario) : new Invalid(problems);
  }

  private static List<String> regexProblems(Expect expect) {
    List<String> problems = new ArrayList<>();
    problems.addAll(regexProblems("expect.reply_matches", expect.replyMatches()));
    problems.addAll(regexProblems("expect.reply_not_matches", expect.replyNotMatches()));
    problems.addAll(regexProblems("expect.transcript_matches", expect.transcriptMatches()));
    if (expect.everyReply() != null) {
      problems.addAll(
          regexProblems(
              "expect.every_reply.reply_not_matches", expect.everyReply().replyNotMatches()));
    }
    return problems;
  }

  /** Each entry in the grammar {@link StopWhen} reads, so no trial can stop on a typo. */
  private static List<String> stopWhenProblems(Scenario.Story story) {
    try {
      StopWhen.parseAll(story.stopWhen());
      return List.of();
    } catch (ScenarioException e) {
      return List.of(e.getMessage());
    }
  }

  private static List<String> regexProblems(String field, List<String> regexes) {
    List<String> problems = new ArrayList<>();
    for (String regex : regexes == null ? List.<String>of() : regexes) {
      try {
        Pattern.compile(regex);
      } catch (PatternSyntaxException e) {
        problems.add(field + " has a bad regex: " + e.getDescription());
      }
    }
    return problems;
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }
}
