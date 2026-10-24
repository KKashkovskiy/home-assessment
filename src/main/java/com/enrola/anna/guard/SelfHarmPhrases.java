package com.enrola.anna.guard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Clear phrases of suicidal intent or self-harm, as regexes from {@code
 * guards/self-harm-phrases.yaml}, found anywhere in a message: what the self-harm pre-guard reads
 * when the moderation endpoint did not answer. Narrow on purpose, so figurative speech such as
 * "this premium is killing me" does not match. Each rule carries match and no-match examples, and
 * loading checks every one, so a file that contradicts its examples stops the app at boot.
 */
public final class SelfHarmPhrases {

  private static final String RESOURCE = "guards/self-harm-phrases.yaml";
  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

  /** The file spells fields snake_case; a misspelt field is an error, not a missing example. */
  private static final YAMLMapper YAML =
      YAMLMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  /** The file: its rules in order, as written. */
  record PhraseFile(List<WrittenRule> rules) {

    PhraseFile {
      rules = Objects.requireNonNullElse(rules, List.of());
    }
  }

  /**
   * One rule as written; a list the file leaves out is empty, so the checks name what is missing.
   */
  record WrittenRule(String id, List<String> patterns, List<String> match, List<String> noMatch) {

    WrittenRule {
      patterns = Objects.requireNonNullElse(patterns, List.of());
      match = Objects.requireNonNullElse(match, List.of());
      noMatch = Objects.requireNonNullElse(noMatch, List.of());
    }
  }

  /** A rule with its patterns compiled once. */
  private record Rule(String id, List<Pattern> patterns, List<String> match, List<String> noMatch) {

    boolean matches(String text) {
      return patterns.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }
  }

  private final List<Rule> rules;

  private SelfHarmPhrases(List<Rule> rules) {
    this.rules = rules;
  }

  /** The list shipped in the jar; a missing, malformed or failing file throws. */
  public static SelfHarmPhrases bundled() {
    try {
      return load(new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException(RESOURCE + " is unreadable", e);
    }
  }

  /**
   * The list in {@code yaml}. Throws naming every problem when the file has no rules, a pattern
   * does not compile, a rule has fewer than two examples of a kind, a match example its rule does
   * not match, a no-match example any rule matches, or a pattern none of its match examples
   * exercises.
   */
  static SelfHarmPhrases load(String yaml) {
    List<Rule> rules = read(yaml).rules().stream().map(SelfHarmPhrases::compile).toList();
    if (rules.isEmpty()) {
      throw new IllegalStateException(RESOURCE + " has no rules");
    }
    SelfHarmPhrases list = new SelfHarmPhrases(rules);
    List<String> problems = rules.stream().flatMap(list::problems).toList();
    if (!problems.isEmpty()) {
      throw new IllegalStateException(RESOURCE + ": " + String.join("; ", problems));
    }
    return list;
  }

  /** Whether {@code text} carries a phrase of any rule. */
  public boolean matches(String text) {
    return rules.stream().anyMatch(rule -> rule.matches(text));
  }

  private Stream<String> problems(Rule rule) {
    return Stream.of(
            tooFewExamples(rule),
            unmatchedExamples(rule),
            matchedNoMatches(rule),
            unexercisedPatterns(rule))
        .flatMap(Function.identity());
  }

  private static Stream<String> tooFewExamples(Rule rule) {
    return rule.match().size() < 2 || rule.noMatch().size() < 2
        ? Stream.of(rule.id() + " needs at least two match and two no_match examples")
        : Stream.empty();
  }

  private static Stream<String> unmatchedExamples(Rule rule) {
    return rule.match().stream()
        .filter(example -> !rule.matches(example))
        .map(example -> rule.id() + " does not match its example: " + example);
  }

  /** Checked against the whole list, since a match by any rule sends the template. */
  private Stream<String> matchedNoMatches(Rule rule) {
    return rule.noMatch().stream()
        .filter(this::matches)
        .map(example -> rule.id() + " no_match is matched: " + example);
  }

  private static Stream<String> unexercisedPatterns(Rule rule) {
    return IntStream.range(0, rule.patterns().size())
        .filter(index -> rule.match().stream().noneMatch(rule.patterns().get(index).asPredicate()))
        .mapToObj(index -> rule.id() + " pattern " + (index + 1) + " has no match example");
  }

  private static Rule compile(WrittenRule written) {
    List<Pattern> patterns = new ArrayList<>();
    for (String regex : written.patterns()) {
      try {
        patterns.add(Pattern.compile(regex, FLAGS));
      } catch (PatternSyntaxException e) {
        throw new IllegalStateException(
            "%s: %s pattern %d does not compile: %s"
                .formatted(RESOURCE, written.id(), patterns.size() + 1, e.getDescription()),
            e);
      }
    }
    return new Rule(written.id(), List.copyOf(patterns), written.match(), written.noMatch());
  }

  private static PhraseFile read(String yaml) {
    try {
      return YAML.readValue(yaml, PhraseFile.class);
    } catch (JacksonException e) {
      throw new IllegalStateException(RESOURCE + ": " + e.getOriginalMessage(), e);
    }
  }
}
