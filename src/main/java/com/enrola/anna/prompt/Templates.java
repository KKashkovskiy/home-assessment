package com.enrola.anna.prompt;

import com.enrola.anna.config.PersonaProperties;
import com.enrola.anna.lead.Lead;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * The ten fixed SMS templates of one prompt bundle, loaded by {@link PromptLoader}. Construction
 * fails on a missing file, a character outside {@link Gsm7}, an unknown placeholder or a rendering
 * over the template's cap, so a bad edit fails boot before the first lead sees it.
 */
public class Templates {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

  /**
   * The widest lead schema.sql can store: an 80-char {@code given_name} and a 40-char {@code
   * current_provider}. Every template renders it at boot, so no stored lead can exceed a cap.
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

  /** Loads {@code prefix + NAME.txt} for every template; {@code prefix} ends in a slash. */
  public Templates(ResourceLoader loader, String prefix, PersonaProperties persona) {
    this.persona = persona;
    for (Template template : Template.values()) {
      texts.put(template, load(template, loader.getResource(prefix + template.name() + ".txt")));
      render(template, WIDEST_STORABLE_LEAD);
    }
  }

  /**
   * Fills {name_part}, {persona}, {disclosure} and {provider} in one pass, so a value that looks
   * like a placeholder stays literal. Throws when the result is over the template's cap.
   */
  public String render(Template template, Lead lead) {
    Map<String, String> values =
        Map.of(
            "name_part", namePart(lead),
            "persona", persona.name(),
            "disclosure", disclosure(),
            "provider", Objects.requireNonNullElse(lead.currentProvider(), "").strip());
    String text =
        PLACEHOLDER
            .matcher(texts.get(template))
            .replaceAll(
                found -> Matcher.quoteReplacement(placeholderValue(template, found, values)));
    if (text.length() > template.maxChars) {
      throw new IllegalStateException(
          template + " renders to " + text.length() + " chars, cap " + template.maxChars);
    }
    return text;
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

  private static String load(Template template, Resource resource) {
    String text;
    try {
      text = resource.getContentAsString(StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new IllegalStateException(
          template + " template unreadable: " + resource.getDescription(), e);
    }
    if (!Gsm7.isClean(text)) {
      throw new IllegalStateException(template + " has non-GSM-7 characters");
    }
    return text;
  }
}
