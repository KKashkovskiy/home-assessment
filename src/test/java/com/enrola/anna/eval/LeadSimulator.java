package com.enrola.anna.eval;

import com.enrola.anna.agent.InputItem;
import com.enrola.anna.agent.ModelClient;
import com.enrola.anna.agent.ModelRequest;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.PriceTable;
import com.enrola.anna.conversation.Direction;
import com.enrola.anna.conversation.Message;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The simulated lead: one SMS per call from a story's hidden persona, on a client and model of its
 * own, never Anna's model bean, so its spend is its own line in the trial's cost; a model the price
 * table cannot price is refused, since its spend would pass the cap unseen. The line comes back
 * through the same strict-schema transport Anna uses, with a one-field schema. A line that is
 * empty, far too long, claims to be an AI or is one the persona forbids is a {@link
 * Simulator.PersonaBreak}, and so is a failed call.
 */
final class LeadSimulator implements Simulator {

  /**
   * The actor's instructions, {@code evals/prompts/simulator.md}, and what is added once the story
   * reached a terminal state, {@code simulator-closing.md}.
   */
  record Rules(String always, String afterEnd) {

    static Rules fromFiles() {
      return new Rules(read("simulator.md"), read("simulator-closing.md"));
    }

    private static String read(String name) {
      Path file = Path.of("evals/prompts", name);
      try {
        return Files.readString(file).strip();
      } catch (IOException e) {
        throw new UncheckedIOException("cannot read " + file, e);
      }
    }
  }

  /**
   * {@code {"text": string}}, strict, so the line arrives as JSON the transport already handles.
   */
  static final JsonNode LEAD_LINE_SCHEMA =
      JsonMapper.builder()
          .build()
          .readTree(
              """
              {"type": "object", "additionalProperties": false, "required": ["text"],
               "properties": {"text": {"type": "string"}}}
              """);

  private static final Pattern AI_CLAIM =
      Pattern.compile("(?i)\\b(as an ai|i am an ai|i'm an ai|language model)\\b");
  private static final int MAX_LINE_CHARS = 600;

  /** Room for the reasoning tokens, which count toward the cap, as well as the line. */
  private static final int MAX_OUTPUT_TOKENS = 1000;

  private final ModelClient actor;
  private final String model;
  private final PriceTable prices;
  private final Rules rules;

  LeadSimulator(ModelClient actor, String model, PriceTable prices, Rules rules) {
    if (!prices.prices().containsKey(model)) {
      throw new IllegalArgumentException(
          "the simulated lead's model "
              + model
              + " has no price in the settings' price table, so its spend would not count;"
              + " price it on the Configuration page or set eval.simulator.model to a priced one");
    }
    this.actor = actor;
    this.model = model;
    this.prices = prices;
    this.rules = rules;
  }

  /** A model can play any persona. */
  @Override
  public boolean plays(Persona persona) {
    return true;
  }

  @Override
  public Next next(Persona persona, List<Message> transcript, Moment moment) {
    ModelResponse response = actor.call(request(persona, transcript, moment));
    BigDecimal cost = prices.cost(model, response.usage());
    return switch (response) {
      case ModelResponse.Decided decided ->
          checked(decided.output().path("text").asString("").strip(), persona, cost);
      case ModelResponse.Failed failed ->
          new PersonaBreak("actor call failed: " + failed.code() + " " + failed.detail(), cost);
      case ModelResponse.ToolCalls _ ->
          new PersonaBreak("actor call failed: it called a tool, and none is offered", cost);
    };
  }

  /** {@code line} as the lead's, or the persona break it is. */
  private static Next checked(String line, Persona persona, BigDecimal cost) {
    return unusable(line, persona)
        .<Next>map(reason -> new PersonaBreak(reason, cost))
        .orElseGet(() -> new Line(line, cost));
  }

  private ModelRequest request(Persona persona, List<Message> transcript, Moment moment) {
    String input =
        """
        <persona>
        %s
        </persona>
        <transcript>
        %s
        </transcript>
        Write the lead's next SMS. One message, plain text, nothing else.
        """
            .formatted(persona.text(), render(transcript));
    return new ModelRequest(
        model,
        switch (moment) {
          case TALKING -> rules.always();
          case AFTER_END -> rules.always() + "\n\n" + rules.afterEnd();
        },
        List.of(new InputItem.UserText(input)),
        "lead_line",
        LEAD_LINE_SCHEMA,
        List.of(),
        "low",
        null,
        MAX_OUTPUT_TOKENS);
  }

  /** Why {@code line} cannot be sent as the lead's, if it cannot. */
  private static Optional<String> unusable(String line, Persona persona) {
    if (line.isEmpty()) {
      return Optional.of("empty line");
    }
    if (line.length() > MAX_LINE_CHARS) {
      return Optional.of("line over " + MAX_LINE_CHARS + " chars");
    }
    if (AI_CLAIM.matcher(line).find()) {
      return Optional.of("actor claimed to be an AI: " + Checks.quote(line));
    }
    return persona.forbidden().stream()
        .filter(pattern -> pattern.matcher(line).find())
        .findFirst()
        .map(pattern -> "persona forbids " + pattern.pattern() + ": " + Checks.quote(line));
  }

  /** The transcript as the lead sees it: Anna's messages and its own, in order. */
  static String render(List<Message> transcript) {
    return transcript.stream()
        .map(
            message -> (message.direction() == Direction.OUT ? "Anna: " : "You: ") + message.text())
        .collect(Collectors.joining("\n"));
  }
}
