package com.enrola.anna.eval;

import com.enrola.anna.conversation.Conversation;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** When each {@link StopWhen} condition holds, judged after each turn of a story. */
final class StopConditions {

  private static final Pattern SIGN_OFF =
      Pattern.compile("(?i)\\b(leave it there|all the best|take care|bye)\\b");

  private StopConditions() {}

  /**
   * Whether {@code condition} holds given the conversation, the lead messages sent and the reply.
   */
  static boolean holds(
      StopWhen.Condition condition, Conversation conversation, int leadMessages, String lastReply) {
    return switch (condition) {
      case StopWhen.State state ->
          switch (state) {
            case GOAL_MET -> conversation.goalMet;
            case ENDED -> conversation.ended;
            case UNSUBSCRIBED -> conversation.unsubscribed;
            case NEEDS_HUMAN -> conversation.needsHuman;
          };
      case StopWhen.Cap cap -> leadMessages >= cap.leadMessages();
      case StopWhen.Silence _ -> lastReply == null;
      case StopWhen.Goodbye _ -> lastReply != null && SIGN_OFF.matcher(lastReply).find();
    };
  }

  /** The first condition that holds after a turn, if any. */
  static Optional<StopWhen.Condition> firstMet(
      List<StopWhen.Condition> conditions,
      Conversation conversation,
      int leadMessages,
      String lastReply) {
    return conditions.stream()
        .filter(condition -> holds(condition, conversation, leadMessages, lastReply))
        .findFirst();
  }
}
