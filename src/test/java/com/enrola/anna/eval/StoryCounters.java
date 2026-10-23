package com.enrola.anna.eval;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.conversation.Conversation;

/**
 * What a story counts across its turns. Retries are the objections Anna answered with a retry:
 * every counted objection but the one that closed the conversation DECLINED. Replies after the goal
 * and after the end are those sent on a turn that began with the goal already met or the
 * conversation already ended, so the booking confirmation and the closing template are not counted,
 * and the probes are what the limits measure.
 */
final class StoryCounters {

  private int retries;
  private int afterGoal;
  private int afterEnd;
  private boolean goalMetBefore;
  private boolean endedBefore;

  /** Counts one turn: the conversation after it and how many replies it sent. */
  void observe(Conversation after, int replies) {
    retries = Math.max(0, after.objectionsUsed - (after.endReason == EndReason.DECLINED ? 1 : 0));
    afterGoal += goalMetBefore ? replies : 0;
    afterEnd += endedBefore ? replies : 0;
    goalMetBefore = after.goalMet;
    endedBefore = after.ended;
  }

  int retries() {
    return retries;
  }

  int afterGoal() {
    return afterGoal;
  }

  int afterEnd() {
    return afterEnd;
  }
}
