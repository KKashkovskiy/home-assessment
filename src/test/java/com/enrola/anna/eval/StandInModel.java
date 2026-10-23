package com.enrola.anna.eval;

import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.InputItem;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.ModelClient;
import com.enrola.anna.agent.ModelRequest;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.eval.Scenario.Expect;
import com.enrola.anna.scheduling.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The offline model of one turn trial: it derives each answer from its scenario's {@code expect}
 * block, so a run with no key exercises the seeder, the pipeline, the guards, the checks and the
 * report at $0. It calls the tools the scenario expects, then decides with the expected intent,
 * stage, flags and handoff and a reply that satisfies {@code reply_matches}. It proves nothing
 * about the prompt, and says so: a regex it cannot satisfy is a {@link ScenarioException}, and a
 * scenario that asserts only what a reply must not say passes trivially.
 */
final class StandInModel implements ModelClient {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The one question a reply that does not close the conversation ends with. */
  static final String QUESTION = "Does that work for you?";

  /** What is left of a regex once the escapes scenarios use are removed must be plain text. */
  private static final Pattern META = Pattern.compile("[()\\[\\]{}|*+?\\\\]");

  private final Scenario.Turn scenario;
  private final List<Slot> seededOffer;
  private final boolean seededEnded;

  /**
   * For {@code scenario}, and its seeded conversation for the latest offer and whether it has
   * already ended, when a reply closes and asks nothing.
   */
  StandInModel(Scenario.Turn scenario, Conversation seeded) {
    this.scenario = scenario;
    this.seededOffer = seeded.lastOfferedSlots();
    this.seededEnded = seeded.ended;
  }

  @Override
  public ModelResponse call(ModelRequest request) {
    Expect expect = scenario.expect();
    Set<String> wanted = expect.toolCalled() == null ? Set.of() : Set.copyOf(expect.toolCalled());
    Set<String> called = StandInDecisions.calledTools(request);
    List<Slot> offer = latestOffer(request).orElse(seededOffer);
    if (wanted.contains("book_call") && !called.contains("book_call")) {
      return StandInDecisions.toolCall("book_call", Map.of("slot_id", firstSlot(offer).slotId()));
    }
    if (wanted.contains("get_availability") && !called.contains("get_availability")) {
      return StandInDecisions.toolCall(
          "get_availability",
          StandInDecisions.availabilityArgs(scenario.message(), scenario.clock().toLocalDate()));
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

  private static ModelResponse decided(Expect expect, String reply) {
    return StandInDecisions.decided(
        new StandInDecisions.Answer(
            expect.leadIntent() == null ? LeadIntent.ENGAGED : expect.leadIntent(),
            expect.stage() == null ? Stage.ENGAGE : expect.stage(),
            expect.flags() == null ? List.of() : expect.flags(),
            reply,
            Boolean.TRUE.equals(expect.unsubscribed()),
            Boolean.TRUE.equals(expect.needsHuman()),
            expect.handoffReason() == null ? HandoffReason.NONE : expect.handoffReason()));
  }

  /** The slots of this turn's latest get_availability output, in offer order; empty before one. */
  private static Optional<List<Slot>> latestOffer(ModelRequest request) {
    return StandInDecisions.inputsOf(request, InputItem.FunctionOutput.class).stream()
        .map(output -> JSON.readTree(output.outputJson()).path("slots"))
        .filter(JsonNode::isArray)
        .map(StandInModel::slotsIn)
        .reduce((earlier, later) -> later);
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
  private Slot firstSlot(List<Slot> offer) {
    if (offer.isEmpty()) {
      throw new ScenarioException(scenario.id() + ": book_call expected but no slot was offered");
    }
    return offer.getFirst();
  }
}
