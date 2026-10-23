package com.enrola.anna.prompt;

import com.enrola.anna.config.Settings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * The rules a prompt version must pass to be saved, seeded or loaded for a turn. An error is
 * anything that would stop a turn on the version from being answered: a placeholder missing or
 * unknown, JSON that does not parse, a schema or tool the client cannot send, a template that does
 * not render for every storable lead. A template outside GSM-7 is a warning: the handset still gets
 * it, in more segments.
 */
public final class PromptValidation {

  private static final Set<String> TOOLS = Set.of("get_availability", "book_call");

  private PromptValidation() {}

  /** What the rules found: errors refuse the version, warnings are reported with it. */
  public record Result(List<String> errors, List<String> warnings) {

    static final Result NONE = new Result(List.of(), List.of());

    public Result {
      errors = List.copyOf(errors);
      warnings = List.copyOf(warnings);
    }

    static Result ofErrors(List<String> errors) {
      return new Result(errors, List.of());
    }

    /** No errors: the version may be saved, activated and loaded. */
    public boolean ok() {
      return errors.isEmpty();
    }

    /** Neither errors nor warnings: what the repo's own versions must be. */
    public boolean clean() {
      return errors.isEmpty() && warnings.isEmpty();
    }

    Result plus(Result other) {
      return new Result(
          Stream.concat(errors.stream(), other.errors.stream()).toList(),
          Stream.concat(warnings.stream(), other.warnings.stream()).toList());
    }
  }

  /** The persona counts: the disclosure is part of every rendered opener. */
  public static Result validate(PromptVersion version, Settings.Persona persona) {
    return validate(version, version.parse(), persona);
  }

  /** The same rules on {@code version} with its JSON columns already parsed. */
  static Result validate(
      PromptVersion version, PromptVersion.Columns columns, Settings.Persona persona) {
    return Result.ofErrors(PromptBundle.placeholderProblems(version.promptMd()))
        .plus(knowledge(version.knowledgeMd()))
        .plus(
            columns
                .schema()
                .map(PromptValidation::schema)
                .orElse(unparsed("decision schema is not valid JSON")))
        .plus(
            columns
                .tools()
                .map(PromptValidation::tools)
                .orElse(unparsed("tools are not valid JSON")))
        .plus(
            columns
                .templates()
                .map(texts -> templates(texts, persona))
                .orElse(unparsed("templates are not a JSON object of name to text")));
  }

  private static Result unparsed(String error) {
    return Result.ofErrors(List.of(error));
  }

  private static Result knowledge(String knowledge) {
    return knowledge.isBlank() ? Result.ofErrors(List.of("knowledge pack is empty")) : Result.NONE;
  }

  /** The decision call sends {@code schema} under {@code name}, strict. */
  private static Result schema(JsonNode schema) {
    List<String> errors = new ArrayList<>();
    if (!"agent_decision".equals(schema.path("name").asString())) {
      errors.add("decision schema name must be agent_decision");
    }
    if (!schema.path("strict").asBoolean()) {
      errors.add("decision schema must be strict");
    }
    if (!schema.path("schema").isObject()) {
      errors.add("decision schema must have a schema object");
    }
    return Result.ofErrors(errors);
  }

  /** Each tool is sent by name with its description and parameters, strict. */
  private static Result tools(JsonNode tools) {
    if (!tools.isArray()) {
      return Result.ofErrors(List.of("tools must be a JSON array"));
    }
    List<String> errors = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (JsonNode tool : tools) {
      String name = tool.path("name").asString();
      names.add(name);
      if (!tool.path("strict").asBoolean()) {
        errors.add("tool " + name + " must be strict");
      }
      if (!tool.path("description").isString() || !tool.path("parameters").isObject()) {
        errors.add("tool " + name + " must have a description and a parameters object");
      }
    }
    if (!names.containsAll(TOOLS)) {
      errors.add("tools must define get_availability and book_call");
    }
    return Result.ofErrors(errors);
  }

  private static Result templates(Map<String, String> texts, Settings.Persona persona) {
    List<String> missing =
        Stream.of(Template.values())
            .filter(template -> texts.get(template.name()) == null)
            .map(template -> "templates are missing " + template)
            .toList();
    if (!missing.isEmpty()) {
      return Result.ofErrors(missing);
    }
    Templates templates = new Templates(texts, persona);
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    for (Template template : Template.values()) {
      templates.problem(template).ifPresent(errors::add);
      if (!Gsm7.isClean(templates.text(template))) {
        warnings.add(template + " has characters outside GSM-7");
      }
    }
    return new Result(errors, warnings);
  }
}
