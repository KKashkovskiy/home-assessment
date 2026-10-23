package com.enrola.anna.eval;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.InputItem;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.ModelClient;
import com.enrola.anna.agent.ModelRequest;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.ModelResponse.Usage;
import com.enrola.anna.agent.ObjectionType;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.eval.Scenario.Expect;
import com.enrola.anna.scheduling.Slot;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The offline model: it derives each answer from the armed scenario's {@code expect} block, so a
 * run with no key exercises the seeder, the pipeline, the guards, the checks and the report at $0.
 * It calls the tools the scenario expects, then decides with the expected intent, stage, flags and
 * handoff and a reply that satisfies {@code reply_matches}. It proves nothing about the prompt, and
 * says so: a regex it cannot satisfy is a {@link ScenarioException}, and a scenario that asserts
 * only what a reply must not say passes trivially.
 */
final class StandInModel implements ModelClient {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The one question a reply that does not close the conversation ends with. */
  static final String QUESTION = "Does that work for you?";

  /** What is left of a regex once the escapes scenarios use are removed must be plain text. */
  private static final Pattern META = Pattern.compile("[()\\[\\]{}|*+?\\\\]");

  private volatile Scenario.Turn scenario;
  private volatile List<Slot> seededOffer = List.of();
  private volatile boolean seededEnded;

  /**
   * Set before each trial: the scenario, and its seeded conversation for the latest offer and
   * whether it has already ended, when a reply closes and asks nothing.
   */
  void arm(Scenario.Turn next, Conversation seeded) {
    scenario = next;
    seededOffer = seeded.lastOfferedSlots();
    seededEnded = seeded.ended;
  }

  @Override
  public ModelResponse call(ModelRequest request) {
    Scenario.Turn armed = Objects.requireNonNull(scenario, "arm() first");
    Expect expect = armed.expect();
    Set<String> wanted = expect.toolCalled() == null ? Set.of() : Set.copyOf(expect.toolCalled());
    Set<String> called = calledTools(request);
    List<Slot> offer = latestOffer(request).orElse(seededOffer);
    if (wanted.contains("book_call") && !called.contains("book_call")) {
      return toolCall("book_call", Map.of("slot_id", firstSlot(armed, offer).slotId()));
    }
    if (wanted.contains("get_availability") && !called.contains("get_availability")) {
      return toolCall("get_availability", availabilityArgs(armed));
    }
    String label = offer.isEmpty() ? null : offer.getFirst().label();
    return decided(expect, reply(expect, label));
  }

  /**
   * Nothing when the scenario expects no reply; otherwise a statement that satisfies {@code
   * reply_matches}, and the one question the reply guard asks for unless the reply closes the
   * conversation, where it asks none. A fixed template the product sends instead (a decline, an
   * off-topic close) replaces this text.
   */
  private String reply(Expect expect, String slotLabel) {
    if (Boolean.FALSE.equals(expect.replySent())) {
      return null;
    }
    String statement = statement(expect, slotLabel);
    return seededEnded || Boolean.TRUE.equals(expect.conversationEnded())
        ? statement
        : statement + " " + QUESTION;
  }

  /** A plain opening and one sample per {@code reply_matches} regex. */
  static String statement(Expect expect, String slotLabel) {
    var statement = new StringBuilder("Fair enough.");
    if (expect.replyMatches() != null) {
      expect
          .replyMatches()
          .forEach(regex -> statement.append(' ').append(sample(regex, slotLabel)));
    }
    return statement.toString();
  }

  /**
   * Text a regex finds: the regex itself once the escapes scenarios use are removed, when that is
   * plain text, or else the first offered slot's label, for a clock time. Anything else throws,
   * which beats a silent pass.
   */
  static String sample(String regex, String slotLabel) {
    Pattern pattern = Pattern.compile(regex);
    String literal =
        regex
            .replace("(?i)", "")
            .replace("\\b", "")
            .replace("\\s", " ")
            .replace("\\.", ".")
            .replace("\\$", "$")
            .replaceAll("^\\^|\\$$", "");
    if (!META.matcher(literal).find() && pattern.matcher(literal).find()) {
      return literal;
    }
    if (slotLabel != null && pattern.matcher(slotLabel).find()) {
      return slotLabel;
    }
    throw new ScenarioException("stand-in cannot satisfy regex " + regex);
  }

  /**
   * get_availability arguments from the lead's words: a weekday name is the next such day,
   * "tomorrow" the day after the clock, otherwise the coming week; morning, arvo or afternoon, and
   * evening pick the part of the day.
   */
  static Map<String, String> availabilityArgs(Scenario.Turn scenario) {
    String text = scenario.message().toLowerCase(Locale.ROOT);
    LocalDate today = scenario.clock().toLocalDate();
    LocalDate from = today.plusDays(1);
    LocalDate to = today.plusDays(7);
    for (DayOfWeek day : DayOfWeek.values()) {
      if (text.contains(day.name().toLowerCase(Locale.ROOT))) {
        from = next(today, day);
        to = from;
      }
    }
    if (text.contains("tomorrow")) {
      from = today.plusDays(1);
      to = from;
    }
    return Map.of(
        "date_from", from.toString(), "date_to", to.toString(), "part_of_day", partOfDay(text));
  }

  private static ModelResponse decided(Expect expect, String reply) {
    LeadIntent intent = expect.leadIntent() == null ? LeadIntent.ENGAGED : expect.leadIntent();
    var decision =
        new Decision(
            intent,
            expect.stage() == null ? Stage.ENGAGE : expect.stage(),
            intent == LeadIntent.OBJECTION ? ObjectionType.NOT_INTERESTED : ObjectionType.NONE,
            expect.flags() == null ? List.of() : expect.flags(),
            new Decision.Extracted(
                Decision.CoverageGoal.UNKNOWN,
                Decision.CoverType.UNKNOWN,
                null,
                null,
                Decision.TimeOfDay.UNKNOWN),
            "stand-in",
            reply != null,
            reply == null ? "" : reply,
            false,
            Boolean.TRUE.equals(expect.unsubscribed()),
            false,
            EndReason.NONE,
            Boolean.TRUE.equals(expect.needsHuman()),
            expect.handoffReason() == null ? HandoffReason.NONE : expect.handoffReason());
    return new ModelResponse.Decided(JSON.valueToTree(decision), null, Usage.ZERO, 0, "{}", "{}");
  }

  private static Set<String> calledTools(ModelRequest request) {
    return request.input().stream()
        .<String>mapMulti(
            (item, names) -> {
              if (item instanceof InputItem.FunctionCall call) {
                names.accept(call.name());
              }
            })
        .collect(Collectors.toSet());
  }

  /** The slots of this turn's latest get_availability output, in offer order; empty before one. */
  private static Optional<List<Slot>> latestOffer(ModelRequest request) {
    Optional<List<Slot>> latest = Optional.empty();
    for (InputItem item : request.input()) {
      if (item instanceof InputItem.FunctionOutput output) {
        JsonNode slots = JSON.readTree(output.outputJson()).path("slots");
        if (slots.isArray()) {
          latest = Optional.of(slotsIn(slots));
        }
      }
    }
    return latest;
  }

  private static List<Slot> slotsIn(JsonNode slots) {
    List<Slot> parsed = new ArrayList<>();
    slots.forEach(
        slot ->
            parsed.add(
                new Slot(
                    slot.path("slot_id").stringValue(),
                    null,
                    slot.path("local_label").stringValue())));
    return parsed;
  }

  /** The slot a pick books: the first of the latest offer. */
  private static Slot firstSlot(Scenario.Turn armed, List<Slot> offer) {
    if (offer.isEmpty()) {
      throw new ScenarioException(armed.id() + ": book_call expected but no slot was offered");
    }
    return offer.getFirst();
  }

  private static LocalDate next(LocalDate today, DayOfWeek day) {
    LocalDate date = today.plusDays(1);
    while (date.getDayOfWeek() != day) {
      date = date.plusDays(1);
    }
    return date;
  }

  private static String partOfDay(String text) {
    if (text.contains("morning")) {
      return "MORNING";
    }
    if (text.contains("arvo") || text.contains("afternoon")) {
      return "AFTERNOON";
    }
    return text.contains("evening") ? "EVENING" : "ANY";
  }

  private static ModelResponse toolCall(String name, Map<String, String> args) {
    var call = new InputItem.FunctionCall("call-" + name, name, JSON.writeValueAsString(args));
    return new ModelResponse.ToolCalls(List.of(call), Usage.ZERO, 0, "{}", "{}");
  }
}
