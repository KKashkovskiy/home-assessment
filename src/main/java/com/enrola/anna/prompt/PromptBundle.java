package com.enrola.anna.prompt;

import com.enrola.anna.config.PersonaProperties;
import com.enrola.anna.config.SmsProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * One prompt version's artifacts the turns read: the prompt, the knowledge pack, the decision
 * schema, the function tools ({@code tools.json}, one entry each) and the fixed templates.
 * Immutable. {@code canary} is a random token per boot that the prompt tells the model to keep
 * secret, so a reply that contains it is a leak. A prompt with a placeholder {@link #render} does
 * not fill is rejected here, at boot.
 */
public record PromptBundle(
    String id,
    String prompt,
    String knowledge,
    JsonNode schema,
    List<JsonNode> tools,
    Templates templates,
    String canary) {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
  private static final Set<String> PLACEHOLDERS =
      Set.of("persona", "target_chars", "max_chars", "canary", "knowledge");

  public PromptBundle {
    tools = List.copyOf(tools);
    Matcher found = PLACEHOLDER.matcher(prompt);
    while (found.find()) {
      if (!PLACEHOLDERS.contains(found.group(1))) {
        throw new IllegalArgumentException(
            "prompt " + id + " has unknown placeholder " + found.group());
      }
    }
  }

  /**
   * The instructions of every call: placeholders filled and the pack inlined in one pass, so text
   * in a value that looks like a placeholder stays literal.
   */
  public String render(PersonaProperties persona, SmsProperties sms) {
    Map<String, String> values =
        Map.of(
            "persona", persona.name(),
            "target_chars", String.valueOf(sms.targetChars()),
            "max_chars", String.valueOf(sms.maxChars()),
            "canary", canary,
            "knowledge", knowledge);
    return PLACEHOLDER
        .matcher(prompt)
        .replaceAll(found -> Matcher.quoteReplacement(values.get(found.group(1))));
  }
}
