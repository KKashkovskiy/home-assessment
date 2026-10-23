package com.enrola.anna.conversation;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.guard.PreGuards;
import com.enrola.anna.guard.Suppressions;
import com.enrola.anna.lead.Suppression;
import com.enrola.anna.prompt.PromptLoader;
import com.enrola.anna.prompt.Template;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What code does when it, not the model, decides a turn: what the conversation becomes and what is
 * sent. One method per outcome, shared by the pre-guards and by the model's own OPT_OUT, ABUSIVE
 * and SELF_HARM intents: two detectors, one outcome. Replies here are fixed templates, never the
 * model's words.
 */
@Component
class GuardOutcomes {

  private final Suppressions suppressions;
  private final TurnLocks locks;
  private final PromptLoader prompts;
  private final TraceLog trace;

  GuardOutcomes(Suppressions suppressions, TurnLocks locks, PromptLoader prompts, TraceLog trace) {
    this.suppressions = suppressions;
    this.locks = locks;
    this.prompts = prompts;
    this.trace = trace;
  }

  /**
   * The outcome code owns for {@code intent} whoever detected it, with the model's reply traced as
   * discarded; empty for an intent the model's reply may answer.
   */
  Optional<TurnOutcome> codeOwned(RunningTurn turn, LeadIntent intent) {
    Optional<TurnOutcome> outcome =
        switch (intent) {
          case OPT_OUT -> Optional.of(unsubscribe(turn, Suppression.Reason.MODEL));
          case ABUSIVE -> Optional.of(endAbusive(turn, PreGuards.Detector.MODEL));
          case SELF_HARM -> Optional.of(safety(turn));
          case ENGAGED,
              QUESTION,
              OBJECTION,
              OFF_TOPIC,
              INJECTION,
              AI_QUESTION,
              DISTRESS,
              PLEASANTRY,
              HANDOFF_REQUEST,
              RESCHEDULE,
              OTHER ->
              Optional.empty();
        };
    outcome.ifPresent(
        _ ->
            trace.record(
                turn,
                TraceLog.Entry.guard(
                    "reply-discarded", TraceOutcome.OVERRIDE, Map.of("lead_intent", intent))));
    return outcome;
  }

  /**
   * Suppresses the phone, ends UNSUBSCRIBED and confirms, even after another end: compliance beats
   * silence. The suppression is written under the lead's lock, so a start for the same lead cannot
   * pass its check in between; a busy lead is reported as this conversation being busy, since the
   * platform retries the message.
   */
  TurnOutcome unsubscribe(RunningTurn turn, Suppression.Reason reason) {
    Conversation conversation = turn.conversation();
    try {
      locks.forLead(turn.lead().id(), () -> suppressions.record(turn.lead(), reason, turn.now()));
    } catch (BusyException _) {
      throw BusyException.conversation(conversation.id);
    }
    StateRules.end(conversation, EndReason.UNSUBSCRIBED, turn.now());
    return fixed(turn, Template.UNSUBSCRIBE_CONFIRMATION, "opt-out " + reason);
  }

  /** Ends ABUSIVE with no reply, by requirement. */
  TurnOutcome endAbusive(RunningTurn turn, PreGuards.Detector detector) {
    trace.record(
        turn, TraceLog.Entry.guard("abuse", TraceOutcome.BLOCK, Map.of("detector", detector)));
    StateRules.end(turn.conversation(), EndReason.ABUSIVE, turn.now());
    return TurnOutcome.silent("abuse by " + detector);
  }

  /**
   * The fixed safety template, which carries no marketing, so it may reach an opted-out lead. It
   * goes out once: the pre-guards check the history for SAFETY before routing here.
   */
  TurnOutcome safety(RunningTurn turn) {
    turn.conversation().addFlag(Flag.VULNERABLE);
    StateRules.safety(turn.conversation(), turn.now());
    return fixed(turn, Template.SELF_HARM_SAFETY, "self-harm");
  }

  /** Ends with {@code reason} and sends {@code template}, which is the one closing reply. */
  TurnOutcome fixedClose(RunningTurn turn, Template template, EndReason reason) {
    StateRules.end(turn.conversation(), reason, turn.now());
    return fixed(turn, template, "close " + reason);
  }

  /**
   * After a soft-terminal end: a human is asked to act on the lead's request, and the lead told.
   */
  TurnOutcome handoffAck(RunningTurn turn, HandoffReason reason) {
    StateRules.handoff(turn.conversation(), reason);
    return fixed(turn, Template.HANDOFF_ACK, "handoff " + reason);
  }

  /**
   * Reschedule or cancel after a booking: a human moves or cancels the call, the booking stays, and
   * the lead is told.
   */
  TurnOutcome bookingChange(RunningTurn turn) {
    StateRules.handoff(turn.conversation(), HandoffReason.RESCHEDULE);
    return fixed(turn, Template.BOOKING_CHANGE_HANDOFF, "booking change");
  }

  /** The lead repeats a request already acknowledged: no reply, so no loop of acknowledgements. */
  TurnOutcome suppressRepeat(RunningTurn turn) {
    turn.conversation().addFlag(Flag.LOOP_SUPPRESSED);
    return TurnOutcome.silent("repeat suppressed");
  }

  /**
   * The model's reply failed the reply guards and its repair did not fix it; what goes instead
   * follows the state the turn's decision left.
   */
  TurnOutcome replyRejected(RunningTurn turn) {
    TurnOutcome outcome = replacement(turn);
    trace.record(
        turn,
        TraceLog.Entry.guard(
            "repair-failed", TraceOutcome.BLOCK, Map.of("status", outcome.status())));
    return outcome;
  }

  /**
   * After the goal nothing, and a person follows up. On the turn the model ended, the fixed close
   * for that end. While a person is asked to take over, as for distress, nothing: a scripted sales
   * line is wrong there. Otherwise the FALLBACK template.
   */
  private TurnOutcome replacement(RunningTurn turn) {
    Conversation conversation = turn.conversation();
    if (conversation.goalMet) {
      StateRules.handoff(conversation, HandoffReason.ERROR);
      return TurnOutcome.failed("reply rejected after the goal");
    }
    if (conversation.ended) {
      return closeFor(turn, conversation.endReason);
    }
    if (conversation.needsHuman) {
      return TurnOutcome.silent("reply rejected during a handoff");
    }
    return TurnOutcome.fallback(render(turn, Template.FALLBACK));
  }

  /** The model may end DECLINED or HANDOFF; every other end has a code path with its own close. */
  private TurnOutcome closeFor(RunningTurn turn, EndReason reason) {
    return switch (reason) {
      case DECLINED -> TurnOutcome.fallback(render(turn, Template.DECLINED_CLOSE));
      case HANDOFF -> TurnOutcome.fallback(render(turn, Template.HANDOFF_ACK));
      case NONE, BOOKED, UNSUBSCRIBED, ABUSIVE, OFF_TOPIC_LIMIT, MAX_TURNS, SAFETY ->
          TurnOutcome.silent("closing reply rejected");
    };
  }

  /**
   * The model cannot be called: a human looks, nothing is sent, and the conversation stays open.
   */
  TurnOutcome handoffWithoutModel(RunningTurn turn, PreGuards.NoModelReason reason) {
    StateRules.handoff(turn.conversation(), HandoffReason.ERROR);
    return TurnOutcome.silent("no model call: " + reason);
  }

  private TurnOutcome fixed(RunningTurn turn, Template template, String decidedBy) {
    return TurnOutcome.fixed(render(turn, template), decidedBy);
  }

  private String render(RunningTurn turn, Template template) {
    return prompts
        .load(turn.conversation().promptVersion)
        .templates()
        .render(template, turn.lead());
  }
}
