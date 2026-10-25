package com.enrola.anna.eval;

import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.InputItem;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.ModelClient;
import com.enrola.anna.agent.ModelRequest;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.eval.StandInDecisions.Answer;
import com.enrola.anna.scheduling.Slot;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * The offline model of one story turn. It reads the lead's latest line by keyword and answers with
 * a fixed reply the reply guards pass, calling get_availability when the line names a day or a part
 * of one and book_call when it picks from the times already offered. It knows nothing of the
 * story's expectations. The product's own code decides how a story ends: the objection count, the
 * off-topic limit, the closing-reply rule and the booking. Its replies hold the phrases the
 * stories' {@code transcript_matches} look for (free, referral fee, AI assistant), so those pass
 * offline by construction. It proves the story loop at $0, not the prompt.
 */
final class StoryStandIn implements ModelClient {

  /** A line that {@code line} finds is answered with {@code answer}; the first rule wins. */
  private record Rule(Pattern line, Answer answer) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String GOAL_QUESTION =
      "Are you looking to save money or improve your cover?";

  private static final List<Rule> RULES =
      List.of(
          new Rule(
              keywords("ignore (your|all|previous) instructions|\\bprompt\\b"),
              aside(
                  LeadIntent.INJECTION,
                  List.of(Flag.INJECTION_ATTEMPT),
                  HandoffReason.NONE,
                  "I can't share that, but I can help with your cover. " + GOAL_QUESTION)),
          new Rule(
              keywords("\\b(bot|ai|robot|human|real person)\\b"),
              aside(
                  LeadIntent.AI_QUESTION,
                  List.of(Flag.AI_DISCLOSURE_ASKED),
                  HandoffReason.NONE,
                  "Yes, I'm an AI assistant with Comparato, and a real advisor makes the call. "
                      + GOAL_QUESTION)),
          new Rule(
              keywords("diagnos|overwhelm|scared|struggling"),
              aside(
                  LeadIntent.DISTRESS,
                  List.of(Flag.VULNERABLE, Flag.HEALTH_INFO),
                  HandoffReason.DISTRESS,
                  "I'm sorry to hear that, and there's no rush at all. Would it help if someone"
                      + " from the team reached out instead?")),
          new Rule(
              keywords("\\b(joke|poem|rap|song|lol|bro)\\b"),
              aside(
                  LeadIntent.OFF_TOPIC,
                  List.of(Flag.OFF_TOPIC),
                  HandoffReason.NONE,
                  "I'll stick to health cover. " + GOAL_QUESTION)),
          new Rule(
              keywords("\\bfree\\b|make money|get paid|commission|\\bfee\\b"),
              aside(
                  LeadIntent.QUESTION,
                  List.of(),
                  HandoffReason.NONE,
                  "It's free, the same price as going direct, and Comparato gets a referral fee"
                      + " from the fund you pick. Were you after hospital, extras or both?")),
          new Rule(
              keywords("too busy|not interested|said no|no thanks"),
              new Answer(
                  LeadIntent.OBJECTION,
                  Stage.CALL_ASK,
                  List.of(Flag.OBJECTION),
                  "No worries, it's a quick 15 minute call and the advisor does the legwork."
                      + " Would later in the week suit better?",
                  false,
                  false,
                  HandoffReason.NONE)),
          new Rule(
              keywords("^\\W*(ok|okay|thanks|ok thanks|cheers|ta)\\W*$"),
              aside(
                  LeadIntent.PLEASANTRY,
                  List.of(),
                  HandoffReason.NONE,
                  "No worries, there's no rush. Is there anything I can help with?")));

  private static final Pattern PICK =
      keywords("^\\W*\\d{1,2}\\W*$|\\bfirst\\b|that one|\\byes\\b|book it");
  private static final Pattern DAY =
      keywords("day\\b|tomorrow|morning|arvo|afternoon|evening|lunch");

  private final LocalDate today;
  private final List<Slot> offered;
  private final boolean ended;

  /** For the turn about to run on {@code before}, on a story whose clock reads {@code today}. */
  StoryStandIn(LocalDate today, Conversation before) {
    this.today = today;
    this.offered = before.lastOfferedSlots();
    this.ended = before.ended;
  }

  /**
   * After a booking, a confirmation; after an end, a closing reply; otherwise the first rule the
   * line matches, then a pick or a day, then the next stage question.
   */
  @Override
  public ModelResponse call(ModelRequest request) {
    Set<String> called = StandInDecisions.calledTools(request);
    if (called.contains("book_call")) {
      return StandInDecisions.decided(engaged(Stage.BOOKED, confirmation(request)));
    }
    if (called.contains("get_availability")) {
      return StandInDecisions.decided(
          engaged(
              Stage.OFFER_TIMES,
              "I've got a few times that could work. Does the first one suit you?"));
    }
    if (ended) {
      return StandInDecisions.decided(
          aside(LeadIntent.PLEASANTRY, List.of(), HandoffReason.NONE, "No worries, talk soon."));
    }
    String line = latestLine(request);
    return RULES.stream()
        .filter(rule -> rule.line().matcher(line).find())
        .findFirst()
        .map(rule -> StandInDecisions.decided(rule.answer()))
        .orElseGet(() -> schedule(line));
  }

  /** A pick from the offer books it, a day asks the calendar, anything else asks for a day. */
  private ModelResponse schedule(String line) {
    if (!offered.isEmpty() && PICK.matcher(line).find()) {
      return StandInDecisions.toolCall("book_call", Map.of("slot_id", offered.getFirst().slotId()));
    }
    if (DAY.matcher(line).find()) {
      return StandInDecisions.toolCall(
          "get_availability", StandInDecisions.availabilityArgs(line, today));
    }
    return StandInDecisions.decided(
        engaged(
            Stage.CALL_ASK, "Got it. What day would suit a quick 15 minute call with an advisor?"));
  }

  /** The lead's line: the user item before the turn context, which comes last. */
  private static String latestLine(ModelRequest request) {
    List<InputItem.UserText> userTexts =
        StandInDecisions.inputsOf(request, InputItem.UserText.class);
    return userTexts.size() < 2 ? "" : userTexts.get(userTexts.size() - 2).text();
  }

  /** The booked label from book_call's answer, named as the reply guards allow. */
  private static String confirmation(ModelRequest request) {
    String label =
        StandInDecisions.inputsOf(request, InputItem.FunctionOutput.class).stream()
            .map(output -> JSON.readTree(output.outputJson()))
            .filter(answer -> "BOOKED".equals(answer.path("status").asString("")))
            .map(answer -> answer.path("label").asString(""))
            .findFirst()
            .orElseThrow(() -> new ScenarioException("book_call did not book"));
    return "Booked for " + label + ". An advisor will call you then for about 15 minutes.";
  }

  /** An engaged decision at {@code stage}: no flags, no handoff. */
  private static Answer engaged(Stage stage, String reply) {
    return new Answer(
        LeadIntent.ENGAGED, stage, List.of(), reply, false, false, HandoffReason.NONE);
  }

  /** At the opening stage, as a sidetrack leaves it; a handoff asks for a person. */
  private static Answer aside(
      LeadIntent intent, List<Flag> flags, HandoffReason handoff, String reply) {
    return new Answer(
        intent, Stage.ENGAGE, flags, reply, false, handoff != HandoffReason.NONE, handoff);
  }

  private static Pattern keywords(String regex) {
    return Pattern.compile("(?i)" + regex);
  }
}
