package com.enrola.anna.conversation;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.ObjectionType;
import com.enrola.anna.config.LimitsProperties;
import com.enrola.anna.guard.PreGuards;
import com.enrola.anna.guard.StopKeywords;
import com.enrola.anna.prompt.Template;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The counts code keeps from an open conversation's decisions, objections and the off-topic streak,
 * and the close they force once a limit is reached. Only code moves them, so the model cannot talk
 * its way past a limit; the prompt reads them in the turn context.
 */
@Component
class TurnCounters {

  private final LimitsProperties limits;

  TurnCounters(LimitsProperties limits) {
    this.limits = limits;
  }

  /**
   * Counts {@code decision}, whose inbound ends {@code transcript}, and returns the close a reached
   * limit forces. A busy lead naming a day is deferring, not objecting. An engaged turn resets the
   * streak, and a message that repeats the previous one, ignoring case, punctuation and spaces,
   * adds one to it whatever the model called it.
   */
  Optional<PreGuards.FixedClose> count(
      Conversation conversation, Decision decision, List<Message> transcript) {
    if (decision.leadIntent() == LeadIntent.OBJECTION && !isDeferral(decision)) {
      conversation.objectionsUsed++;
    }
    conversation.offTopicStreak = nextStreak(conversation.offTopicStreak, decision.leadIntent());
    if (repeatsPrevious(transcript)) {
      conversation.offTopicStreak++;
    }
    if (conversation.offTopicStreak >= limits.offTopicLimit()) {
      return Optional.of(
          new PreGuards.FixedClose(Template.OFF_TOPIC_CLOSE, EndReason.OFF_TOPIC_LIMIT));
    }
    if (decision.leadIntent() == LeadIntent.OBJECTION
        && conversation.objectionsUsed > limits.objectionRetries()) {
      return Optional.of(new PreGuards.FixedClose(Template.DECLINED_CLOSE, EndReason.DECLINED));
    }
    return Optional.empty();
  }

  private static boolean isDeferral(Decision decision) {
    return decision.objectionType() == ObjectionType.BUSY
        && decision.extracted().preferredDay() != null;
  }

  /** OPT_OUT, ABUSIVE and SELF_HARM never reach here: code owns their outcome. */
  private static int nextStreak(int streak, LeadIntent intent) {
    return switch (intent) {
      case OFF_TOPIC, INJECTION -> streak + 1;
      case ENGAGED, QUESTION, OBJECTION, AI_QUESTION, PLEASANTRY, HANDOFF_REQUEST, RESCHEDULE -> 0;
      case OPT_OUT, ABUSIVE, SELF_HARM, DISTRESS, OTHER -> streak;
    };
  }

  private static boolean repeatsPrevious(List<Message> transcript) {
    List<String> inbound =
        transcript.stream()
            .filter(message -> message.direction() == Direction.IN)
            .map(message -> StopKeywords.normalise(message.text()))
            .toList();
    if (inbound.size() < 2) {
      return false;
    }
    String latest = inbound.getLast();
    return !latest.isEmpty() && latest.equals(inbound.get(inbound.size() - 2));
  }
}
