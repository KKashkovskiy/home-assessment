package com.enrola.anna.prompt;

import com.enrola.anna.config.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * One prompt version's artifacts, parsed for a turn: the prompt, the knowledge pack, the decision
 * schema, the function tools ({@code tools.json}, one entry each) and the fixed templates. Built by
 * {@link PromptStore#bundle} from a row that passed {@link PromptValidation}; string work only.
 * {@code canary} is a random token per boot that the prompt tells the model to keep secret, so a
 * reply that contains it is a leak.
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

  /**
   * What {@link #render} fills each placeholder with: the names a prompt must use, and no others.
   */
  private static final Map<String, Function<Fill, String>> PLACEHOLDERS =
      Map.of(
          "persona", fill -> fill.persona().persona(),
          "target_chars", fill -> String.valueOf(fill.limits().targetChars()),
          "max_chars", fill -> String.valueOf(fill.limits().maxChars()),
          "canary", fill -> fill.bundle().canary(),
          "knowledge", fill -> fill.bundle().knowledge());

  public PromptBundle {
    tools = List.copyOf(tools);
  }

  private record Fill(PromptBundle bundle, Settings.Persona persona, Settings.Limits limits) {}

  /**
   * What {@link #render} needs of a prompt: every placeholder present, and none it would not fill.
   */
  static List<String> placeholderProblems(String prompt) {
    List<String> problems = new ArrayList<>();
    for (String name : PLACEHOLDERS.keySet().stream().sorted().toList()) {
      if (!prompt.contains("{{" + name + "}}")) {
        problems.add("prompt is missing placeholder {{" + name + "}}");
      }
    }
    PLACEHOLDER
        .matcher(prompt)
        .results()
        .filter(found -> !PLACEHOLDERS.containsKey(found.group(1)))
        .forEach(found -> problems.add("prompt has unknown placeholder " + found.group()));
    return problems;
  }

  /**
   * The instructions of every call: placeholders filled and the pack inlined in one pass, so text
   * in a value that looks like a placeholder stays literal.
   */
  public String render(Settings.Persona persona, Settings.Limits limits) {
    Fill fill = new Fill(this, persona, limits);
    return PLACEHOLDER
        .matcher(prompt)
        .replaceAll(
            found -> Matcher.quoteReplacement(PLACEHOLDERS.get(found.group(1)).apply(fill)));
  }
}
