package com.enrola.anna.prompt;

import com.enrola.anna.config.PersonaProperties;
import com.enrola.anna.lead.Lead;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ten fixed SMS templates of one prompt version, keyed by {@link Template} name. {@link
 * PromptValidation} refuses a version with a template {@link #problem} names, so every template of
 * a loaded version renders for every lead the table can store.
 */
public class Templates {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

  /**
   * The widest lead the lead table can store: an 80-char {@code given_name} and a 40-char {@code
   * current_provider}. A template that fits its cap for this lead fits it for any stored lead.
   */
  static final Lead WIDEST_STORABLE_LEAD =
      new Lead(
          "probe",
          "N".repeat(80),
          "+61400000000",
          null,
          null,
          "P".repeat(40),
          null,
          null,
          null,
          Instant.EPOCH);

  private final Map<Template, String> texts = new EnumMap<>(Template.class);
  private final PersonaProperties persona;

  /** {@code texts} maps every {@link Template} name to its text; surrounding space is dropped. */
  public Templates(Map<String, String> texts, PersonaProperties persona) {
    this.persona = persona;
    for (Template template : Template.values()) {
      this.texts.put(
          template,
          Objects.requireNonNull(texts.get(template.name()), () -> template + " missing").strip());
    }
  }

  /** The stored text with its placeholders, as the designer wrote it. */
  public String text(Template template) {
    return texts.get(template);
  }

  /**
   * Fills {name_part}, {persona}, {disclosure} and {provider} in one pass, so a value that looks
   * like a placeholder stays literal. Throws when the result is over the template's cap.
   */
  public String render(Template template, Lead lead) {
    String text = fill(template, values(lead));
    if (text.length() > template.maxChars) {
      throw new IllegalStateException(overCap(template, text.length()));
    }
    return text;
  }

  /**
   * Why {@code template} cannot go to every storable lead: a placeholder {@link #render} does not
   * know, or a rendering for the widest storable lead over the cap. Empty when it can.
   */
  Optional<String> problem(Template template) {
    Map<String, String> values = values(WIDEST_STORABLE_LEAD);
    Optional<String> unknown =
        PLACEHOLDER
            .matcher(texts.get(template))
            .results()
            .filter(found -> !values.containsKey(found.group(1)))
            .map(MatchResult::group)
            .findFirst();
    if (unknown.isPresent()) {
      return Optional.of(template + " has unknown placeholder " + unknown.get());
    }
    int length = fill(template, values).length();
    return length > template.maxChars ? Optional.of(overCap(template, length)) : Optional.empty();
  }

  private Map<String, String> values(Lead lead) {
    return Map.of(
        "name_part", namePart(lead),
        "persona", persona.name(),
        "disclosure", disclosure(),
        "provider", Objects.requireNonNullElse(lead.currentProvider(), "").strip());
  }

  private String fill(Template template, Map<String, String> values) {
    return PLACEHOLDER
        .matcher(texts.get(template))
        .replaceAll(found -> Matcher.quoteReplacement(placeholderValue(template, found, values)));
  }

  private static String overCap(Template template, int length) {
    return template + " renders to " + length + " chars, over " + template.maxChars;
  }

  private static String namePart(Lead lead) {
    String name = lead.givenName();
    return name == null || name.isBlank() ? "" : " " + name.strip();
  }

  private String disclosure() {
    return persona.disclosureInOpener()
        ? ", " + persona.company() + "'s AI assistant"
        : " from " + persona.company();
  }

  private static String placeholderValue(
      Template template, MatchResult found, Map<String, String> values) {
    String value = values.get(found.group(1));
    if (value == null) {
      throw new IllegalStateException(template + " has unknown placeholder " + found.group());
    }
    return value;
  }
}
