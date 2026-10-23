package com.enrola.anna.eval;

import com.enrola.anna.conversation.Conversation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A story's {@code stop_when}: one condition per entry, and any one stops the story. An entry is a
 * state ({@code goal_met}, {@code ended}, {@code unsubscribed}, {@code needs_human}), a cap ({@code
 * N lead messages have been sent} or {@code N turns}), {@code the agent stops replying}, or {@code
 * the agent says it will leave it there or says goodbye}; anything else is a scenario error. A list
 * without a cap gets {@link #DEFAULT_CAP}, so no story talks forever.
 */
final class StopWhen {

  static final int DEFAULT_CAP = 10;

  private static final Pattern CAP =
      Pattern.compile("^(\\d+) (lead messages have been sent|turns)$");

  /** One entry, judged after each turn. */
  sealed interface Condition permits State, Cap, Silence, Goodbye {

    /** Whether it holds given the conversation, the lead messages sent and the last reply. */
    boolean holds(Conversation conversation, int leadMessages, String lastReply);

    /** The entry in a stop reason: {@code goal_met}, {@code message cap}. */
    String describe();
  }

  /** A conversation state reached. */
  enum State implements Condition {
    GOAL_MET,
    ENDED,
    UNSUBSCRIBED,
    NEEDS_HUMAN;

    @Override
    public boolean holds(Conversation conversation, int leadMessages, String lastReply) {
      return switch (this) {
        case GOAL_MET -> conversation.goalMet;
        case ENDED -> conversation.ended;
        case UNSUBSCRIBED -> conversation.unsubscribed;
        case NEEDS_HUMAN -> conversation.needsHuman;
      };
    }

    @Override
    public String describe() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** So many lead messages sent. */
  record Cap(int leadMessages) implements Condition {

    @Override
    public boolean holds(Conversation conversation, int sent, String lastReply) {
      return sent >= leadMessages;
    }

    @Override
    public String describe() {
      return "message cap";
    }
  }

  /** The last turn sent no reply. */
  record Silence() implements Condition {

    @Override
    public boolean holds(Conversation conversation, int leadMessages, String lastReply) {
      return lastReply == null;
    }

    @Override
    public String describe() {
      return "silence";
    }
  }

  /** The last reply signs off. */
  record Goodbye() implements Condition {

    private static final Pattern SIGN_OFF =
        Pattern.compile("(?i)\\b(leave it there|all the best|take care|bye)\\b");

    @Override
    public boolean holds(Conversation conversation, int leadMessages, String lastReply) {
      return lastReply != null && SIGN_OFF.matcher(lastReply).find();
    }

    @Override
    public String describe() {
      return "goodbye";
    }
  }

  private StopWhen() {}

  /** Every entry, with {@link #DEFAULT_CAP} appended when none is a cap. */
  static List<Condition> parseAll(List<String> entries) {
    List<Condition> conditions = new ArrayList<>(entries.stream().map(StopWhen::parse).toList());
    if (conditions.stream().noneMatch(Cap.class::isInstance)) {
      conditions.add(new Cap(DEFAULT_CAP));
    }
    return List.copyOf(conditions);
  }

  /** The lead messages a story with {@code entries} may send before its cap stops it. */
  static int cap(List<String> entries) {
    return parseAll(entries).stream()
        .<Cap>mapMulti(
            (condition, caps) -> {
              if (condition instanceof Cap cap) {
                caps.accept(cap);
              }
            })
        .mapToInt(Cap::leadMessages)
        .min()
        .orElseThrow();
  }

  static Condition parse(String entry) {
    String normal = entry.strip().toLowerCase(Locale.ROOT);
    Matcher cap = CAP.matcher(normal);
    if (cap.matches()) {
      return new Cap(Integer.parseInt(cap.group(1)));
    }
    return switch (normal) {
      case "goal_met", "ended", "unsubscribed", "needs_human" ->
          State.valueOf(normal.toUpperCase(Locale.ROOT));
      case "the agent stops replying" -> new Silence();
      case "the agent says it will leave it there or says goodbye" -> new Goodbye();
      default -> throw new ScenarioException("unknown stop_when: " + entry);
    };
  }

  /** The first condition that holds after a turn, if any. */
  static Optional<Condition> firstMet(
      List<Condition> conditions, Conversation conversation, int leadMessages, String lastReply) {
    return conditions.stream()
        .filter(condition -> condition.holds(conversation, leadMessages, lastReply))
        .findFirst();
  }
}
