package com.enrola.anna.guard;

import com.enrola.anna.lead.Lead;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The prompt's hard rules as regexes, from {@code guards/deny-list.yaml}, applied per sentence. A
 * rule breaks when one of its patterns matches with no negation before it in the same clause, so "I
 * can't promise you'll save" passes and "I can't promise anything, but you'll save hundreds" fails.
 * The file carries match and no-match examples beside each rule, and the test runs them.
 */
public final class DenyList {

  private static final String RESOURCE = "/guards/deny-list.yaml";
  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
  private static final Pattern SENTENCE = Pattern.compile("[^.?!]+[.?!]?");
  private static final String PROVIDER_NONE = "provider_none";

  /** One rule; {@code noProviderOnly} applies it only to a lead with no current fund. */
  record Rule(
      String id,
      List<Pattern> patterns,
      String why,
      boolean noProviderOnly,
      List<String> match,
      List<String> noMatch) {}

  private final Pattern negation;
  private final List<Rule> rules;

  private DenyList(Pattern negation, List<Rule> rules) {
    this.negation = negation;
    this.rules = rules;
  }

  /** The list shipped in the jar. A missing or malformed file throws, so the app cannot boot. */
  public static DenyList bundled() {
    try (InputStream in = DenyList.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is missing");
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException(RESOURCE + " is unreadable", e);
    }
  }

  /** A list from YAML in the shipped file's shape; throws on a missing field or a bad pattern. */
  static DenyList parse(String yaml) {
    if (!(new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml) instanceof Map<?, ?> file)
        || !(file.get("rules") instanceof List<?> entries)) {
      throw new IllegalStateException("deny-list is not a map with a list of rules");
    }
    return new DenyList(
        Pattern.compile(text(file, "negation", "the deny-list"), FLAGS),
        entries.stream().map(DenyList::rule).toList());
  }

  /** The rules in file order, with their examples. */
  List<Rule> rules() {
    return rules;
  }

  /** One violation per sentence that breaks a rule, its note naming the rule. */
  public List<Violation> violations(String reply, Lead lead) {
    return brokenRules(reply, lead).stream()
        .map(rule -> new Violation(ReplyCheck.DENY, rule.id() + ": " + rule.why()))
        .toList();
  }

  /** The first rule each sentence breaks, in sentence order. */
  List<Rule> brokenRules(String reply, Lead lead) {
    return SENTENCE
        .matcher(reply)
        .results()
        .flatMap(sentence -> firstBroken(sentence.group(), lead.hasProvider()).stream())
        .toList();
  }

  private Optional<Rule> firstBroken(String sentence, boolean hasProvider) {
    return rules.stream()
        .filter(rule -> !(rule.noProviderOnly() && hasProvider))
        .filter(rule -> breaks(rule, sentence))
        .findFirst();
  }

  private boolean breaks(Rule rule, String sentence) {
    return rule.patterns().stream()
        .flatMap(pattern -> pattern.matcher(sentence).results())
        .anyMatch(found -> !isNegated(sentence, found.start()));
  }

  /** True when a negation sits between the clause's start and {@code at}. */
  private boolean isNegated(String sentence, int at) {
    return negation.matcher(sentence.substring(Clauses.start(sentence, at), at)).find();
  }

  private static Rule rule(Object entry) {
    if (!(entry instanceof Map<?, ?> fields)) {
      throw new IllegalStateException("a deny-list rule is not a map");
    }
    String id = text(fields, "id", "a deny-list rule");
    return new Rule(
        id,
        patterns(fields, id),
        text(fields, "why", id),
        noProviderOnly(fields, id),
        strings(fields, "match"),
        strings(fields, "no_match"));
  }

  private static List<Pattern> patterns(Map<?, ?> fields, String id) {
    List<String> patterns = strings(fields, "patterns");
    if (patterns.isEmpty()) {
      throw new IllegalStateException(id + " has no patterns");
    }
    return patterns.stream().map(regex -> Pattern.compile(regex, FLAGS)).toList();
  }

  private static boolean noProviderOnly(Map<?, ?> fields, String id) {
    Object onlyWhen = fields.get("only_when");
    if (onlyWhen != null && !PROVIDER_NONE.equals(onlyWhen)) {
      throw new IllegalStateException(
          id + " has only_when " + onlyWhen + "; only provider_none is known");
    }
    return onlyWhen != null;
  }

  private static String text(Map<?, ?> fields, String key, String owner) {
    if (fields.get(key) instanceof String value && !value.isBlank()) {
      return value;
    }
    throw new IllegalStateException(owner + " has no " + key);
  }

  /** A list of strings, empty when missing: a rule without examples fails its test by name. */
  private static List<String> strings(Map<?, ?> fields, String key) {
    return fields.get(key) instanceof List<?> list
        ? list.stream().map(String::valueOf).toList()
        : List.of();
  }
}
