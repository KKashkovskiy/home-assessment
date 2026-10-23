package com.enrola.anna.eval;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.InputItem;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.ModelRequest;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.ModelResponse.Usage;
import com.enrola.anna.agent.ObjectionType;
import com.enrola.anna.agent.Stage;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the offline stand-ins share: a decision as the model returns one, a tool call, the input
 * items of one kind a request carries, and get_availability arguments read from the lead's words.
 */
final class StandInDecisions {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * The fields a stand-in decides; it extracts no facts and never ends a conversation itself, which
   * code does. A null reply sends nothing.
   */
  record Answer(
      LeadIntent intent,
      Stage stage,
      List<Flag> flags,
      String reply,
      boolean unsubscribed,
      boolean needsHuman,
      HandoffReason handoff) {}

  private StandInDecisions() {}

  /** {@code answer} as a round that decided; an OBJECTION is NOT_INTERESTED, never a deferral. */
  static ModelResponse decided(Answer answer) {
    var decision =
        new Decision(
            answer.intent(),
            answer.stage(),
            answer.intent() == LeadIntent.OBJECTION
                ? ObjectionType.NOT_INTERESTED
                : ObjectionType.NONE,
            answer.flags(),
            new Decision.Extracted(
                Decision.CoverageGoal.UNKNOWN,
                Decision.CoverType.UNKNOWN,
                null,
                null,
                Decision.TimeOfDay.UNKNOWN),
            "stand-in",
            answer.reply() != null,
            answer.reply() == null ? "" : answer.reply(),
            false,
            answer.unsubscribed(),
            false,
            EndReason.NONE,
            answer.needsHuman(),
            answer.handoff());
    return new ModelResponse.Decided(JSON.valueToTree(decision), null, Usage.ZERO, 0, "{}", "{}");
  }

  /** A round that calls tool {@code name} with {@code args}. */
  static ModelResponse toolCall(String name, Map<String, String> args) {
    var call = new InputItem.FunctionCall("call-" + name, name, JSON.writeValueAsString(args));
    return new ModelResponse.ToolCalls(List.of(call), Usage.ZERO, 0, "{}", "{}");
  }

  /** The tools called so far this turn. */
  static Set<String> calledTools(ModelRequest request) {
    return inputsOf(request, InputItem.FunctionCall.class).stream()
        .map(InputItem.FunctionCall::name)
        .collect(Collectors.toSet());
  }

  /** The request's input items of {@code type}, in order. */
  static <T extends InputItem> List<T> inputsOf(ModelRequest request, Class<T> type) {
    return request.input().stream().filter(type::isInstance).map(type::cast).toList();
  }

  /**
   * get_availability arguments from the lead's words: a weekday name is the next such day,
   * "tomorrow" the day after {@code today}, otherwise the coming week; morning, arvo or afternoon,
   * and evening pick the part of the day.
   */
  static Map<String, String> availabilityArgs(String message, LocalDate today) {
    String text = message.toLowerCase(Locale.ROOT);
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
}
