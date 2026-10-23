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
   * The model cannot be called: a human looks, nothing is sent, and the conversation stays open.
   */
  TurnOutcome handoffWithoutModel(RunningTurn turn, PreGuards.NoModelReason reason) {
    StateRules.handoff(turn.conversation(), HandoffReason.ERROR);
    return TurnOutcome.silent("no model call: " + reason);
  }

  private TurnOutcome fixed(RunningTurn turn, Template template, String decidedBy) {
    String text =
        prompts.load(turn.conversation().promptVersion).templates().render(template, turn.lead());
    return TurnOutcome.fixed(text, decidedBy);
  }
}
