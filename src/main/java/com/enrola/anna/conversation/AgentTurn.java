package com.enrola.anna.conversation;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.guard.PreGuards;
import com.enrola.anna.guard.ReplyGuards;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The model's part of a turn: one {@link AgentCall}, then what the decision does. On an open
 * conversation code still owns opt-out, abuse, self-harm and the closes its counters force, and the
 * reply goes out through {@link ReplyRepair}; after a soft-terminal end the model only classifies.
 * When the call fails after the SDK's retries, or the output is not a decision, nothing is sent and
 * a human is asked to look.
 */
@Component
class AgentTurn {

  private final AgentCall calls;
  private final DecisionApplier decisions;
  private final TurnCounters counters;
  private final GuardOutcomes outcomes;
  private final ReplyRepair replies;
  private final TraceLog trace;

  AgentTurn(
      AgentCall calls,
      DecisionApplier decisions,
      TurnCounters counters,
      GuardOutcomes outcomes,
      ReplyRepair replies,
      TraceLog trace) {
    this.calls = calls;
    this.decisions = decisions;
    this.counters = counters;
    this.outcomes = outcomes;
    this.replies = replies;
    this.trace = trace;
  }

  /** An open conversation's turn; it stays open when the call fails. */
  TurnOutcome run(RunningTurn turn) {
    AgentCall.Question question = calls.questionFor(turn);
    return switch (calls.ask(turn, question)) {
      case AgentCall.Answer.Decided(Decision decision, Turn.ModelCall call) ->
          answerOpen(turn, decision, question).addModelCall(call);
      case AgentCall.Answer.Failed(ModelResponse.Failure code, Turn.ModelCall call) ->
          failed(turn, code).addModelCall(call);
    };
  }

  /**
   * A soft-terminal conversation's turn: the model classifies, with no tools, since nothing is
   * scheduled after an end. Code-owned intents act as on an open conversation; RESCHEDULE and
   * HANDOFF_REQUEST break the silence once, because ignoring them is a compliance problem, not a
   * loop; anything else gets at most one closing reply. A failed call hands off like one on an open
   * conversation, since the message may be a request no one read.
   */
  TurnOutcome classifyOnly(RunningTurn turn) {
    return switch (calls.classify(turn, calls.questionFor(turn))) {
      case AgentCall.Answer.Decided(Decision decision, Turn.ModelCall call) ->
          classifyEnded(turn, decision).addModelCall(call);
      case AgentCall.Answer.Failed(ModelResponse.Failure code, Turn.ModelCall call) ->
          failed(turn, code).addModelCall(call);
    };
  }

  /** A human is asked to look; nothing is sent. */
  private static TurnOutcome failed(RunningTurn turn, ModelResponse.Failure code) {
    StateRules.handoff(turn.conversation(), HandoffReason.ERROR);
    return TurnOutcome.failed("model call failed: " + code);
  }

  /** Classification, code-owned intents, the counters and the close they force, then the reply. */
  private TurnOutcome answerOpen(RunningTurn turn, Decision decision, AgentCall.Question question) {
    decisions.classify(turn, decision);
    Optional<TurnOutcome> codeOwned = outcomes.codeOwned(turn, decision.leadIntent());
    if (codeOwned.isPresent()) {
      return codeOwned.get();
    }
    Optional<PreGuards.FixedClose> close =
        counters.count(turn.conversation(), decision, question.transcript());
    if (close.isPresent()) {
      return outcomes.fixedClose(turn, close.get().template(), close.get().reason());
    }
    decisions.applyEnding(turn, decision);
    return reply(turn, decision, question);
  }

  /**
   * The model's reply once its ending is applied. No reply before an end is a bad decision, handled
   * like output that is not one, though a handoff the decision asked for is kept.
   */
  private TurnOutcome reply(RunningTurn turn, Decision decision, AgentCall.Question question) {
    Conversation conversation = turn.conversation();
    if (decision.sendReply()) {
      return replies.send(turn, decision.reply(), question);
    }
    if (conversation.ended) {
      return TurnOutcome.silent("model ended without a reply");
    }
    trace.record(
        turn,
        TraceLog.Entry.guard(
            "send-reply-false", TraceOutcome.BLOCK, Map.of("lead_intent", decision.leadIntent())));
    if (!conversation.needsHuman) {
      StateRules.handoff(conversation, HandoffReason.ERROR);
    }
    return TurnOutcome.failed("send_reply false before an end");
  }

  /** A request already acknowledged is not acknowledged again; it falls to the closing reply. */
  private TurnOutcome classifyEnded(RunningTurn turn, Decision decision) {
    decisions.classify(turn, decision);
    Optional<TurnOutcome> codeOwned = outcomes.codeOwned(turn, decision.leadIntent());
    if (codeOwned.isPresent()) {
      return codeOwned.get();
    }
    HandoffReason requested = requestedHandoff(decision.leadIntent());
    if (requested != HandoffReason.NONE && turn.conversation().handoffReason != requested) {
      return outcomes.handoffAck(turn, requested);
    }
    return closingReply(turn, decision);
  }

  /** The handoff an actionable intent asks for: RESCHEDULE, or a person for HANDOFF_REQUEST. */
  private static HandoffReason requestedHandoff(LeadIntent intent) {
    return switch (intent) {
      case RESCHEDULE -> HandoffReason.RESCHEDULE;
      case HANDOFF_REQUEST -> HandoffReason.REQUESTED;
      case ENGAGED,
          QUESTION,
          OBJECTION,
          OPT_OUT,
          ABUSIVE,
          OFF_TOPIC,
          INJECTION,
          AI_QUESTION,
          DISTRESS,
          SELF_HARM,
          PLEASANTRY,
          OTHER ->
          HandoffReason.NONE;
    };
  }

  /**
   * At most one closing reply after an end, and only one the reply guards pass; there is no repair
   * call. Any other is suppressed and flagged.
   */
  private TurnOutcome closingReply(RunningTurn turn, Decision decision) {
    Conversation conversation = turn.conversation();
    if (!decision.sendReply()) {
      return TurnOutcome.silent("model chose silence");
    }
    ReplyGuards.Checked checked = replies.checkClosing(turn, decision.reply());
    boolean sendable =
        checked.passed() && StateRules.closingReplyAllowed(conversation, checked.text());
    Map<String, Integer> counts =
        Map.of(
            "post_terminal_replies",
            conversation.postTerminalReplies,
            "chars",
            checked.text().length());
    trace.record(
        turn,
        TraceLog.Entry.guard(
            "closing-reply", sendable ? TraceOutcome.PASS : TraceOutcome.BLOCK, counts));
    if (sendable) {
      return TurnOutcome.decided(checked.text());
    }
    conversation.addFlag(Flag.LOOP_SUPPRESSED);
    return TurnOutcome.silent("closing reply suppressed");
  }
}
