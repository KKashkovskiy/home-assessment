package com.enrola.anna.conversation;

import com.enrola.anna.agent.AgentService;
import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.ModelRequest;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.TurnContext;
import com.enrola.anna.config.LimitsProperties;
import com.enrola.anna.config.SmsProperties;
import com.enrola.anna.guard.PreGuards;
import com.enrola.anna.prompt.PromptLoader;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The model's part of a turn: asks the agent with the conversation's prompt bundle, the whole
 * transcript and the turn's context, records the call, and acts on the decision. On an open
 * conversation code still owns opt-out, abuse, self-harm and the closes its counters force; after a
 * soft-terminal end the model only classifies. When the call fails after the SDK's retries, or the
 * output is not a decision, nothing is sent and a human is asked to look.
 */
@Component
class AgentTurn {

  private final AgentService agent;
  private final PromptLoader prompts;
  private final MessageRepository messages;
  private final ModelCallRecorder modelCalls;
  private final DecisionApplier decisions;
  private final TurnCounters counters;
  private final GuardOutcomes outcomes;
  private final TraceLog trace;
  private final LimitsProperties limits;
  private final SmsProperties sms;

  AgentTurn(
      AgentService agent,
      PromptLoader prompts,
      MessageRepository messages,
      ModelCallRecorder modelCalls,
      DecisionApplier decisions,
      TurnCounters counters,
      GuardOutcomes outcomes,
      TraceLog trace,
      LimitsProperties limits,
      SmsProperties sms) {
    this.agent = agent;
    this.prompts = prompts;
    this.messages = messages;
    this.modelCalls = modelCalls;
    this.decisions = decisions;
    this.counters = counters;
    this.outcomes = outcomes;
    this.trace = trace;
    this.limits = limits;
    this.sms = sms;
  }

  /** {@code text} cut to its first {@code maxChars} characters when it is longer. */
  static String cut(String text, int maxChars) {
    return text.length() > maxChars ? text.substring(0, maxChars) : text;
  }

  /** An open conversation's turn; it stays open when the call fails. */
  TurnOutcome run(RunningTurn turn) {
    List<Message> transcript = messages.transcript(turn.conversation().id);
    return switch (ask(turn, transcript)) {
      case Answer.Decided(Decision decision, Turn.ModelCall call) ->
          answerOpen(turn, decision, transcript).withModelCall(call);
      case Answer.Failed(ModelResponse.Failure code, Turn.ModelCall call) ->
          failed(turn, code).withModelCall(call);
    };
  }

  /**
   * A soft-terminal conversation's turn: the model classifies. Code-owned intents act as on an open
   * conversation; RESCHEDULE and HANDOFF_REQUEST break the silence once, because ignoring them is a
   * compliance problem, not a loop; anything else gets at most one closing reply. A failed call
   * hands off like one on an open conversation, since the message may be a request no one read.
   */
  TurnOutcome classifyOnly(RunningTurn turn) {
    return switch (ask(turn, messages.transcript(turn.conversation().id))) {
      case Answer.Decided(Decision decision, Turn.ModelCall call) ->
          classifyEnded(turn, decision).withModelCall(call);
      case Answer.Failed(ModelResponse.Failure code, Turn.ModelCall call) ->
          failed(turn, code).withModelCall(call);
    };
  }

  /** What one recorded model call came to, with the turn's model columns either way. */
  private sealed interface Answer {
    record Decided(Decision decision, Turn.ModelCall call) implements Answer {}

    record Failed(ModelResponse.Failure code, Turn.ModelCall call) implements Answer {}
  }

  private Answer ask(RunningTurn turn, List<Message> transcript) {
    Conversation conversation = turn.conversation();
    ModelResponse response =
        agent.decide(prompts.load(conversation.promptVersion), input(transcript), contextFor(turn));
    return switch (response) {
      case ModelResponse.Decided decided -> decisionOf(turn, decided);
      case ModelResponse.Failed failed -> failure(turn, failed);
    };
  }

  /** Output that is not a decision is recorded as a SCHEMA failure. */
  private Answer decisionOf(RunningTurn turn, ModelResponse.Decided decided) {
    Decision decision;
    try {
      decision = decided.decision();
    } catch (ModelResponse.SchemaMismatch e) {
      return failure(turn, decided.failed(ModelResponse.Failure.SCHEMA, e.getMessage()));
    }
    return new Answer.Decided(
        decision, modelCalls.record(turn.conversation(), turn.turnNo(), decided));
  }

  private Answer failure(RunningTurn turn, ModelResponse.Failed failed) {
    return new Answer.Failed(
        failed.code(), modelCalls.record(turn.conversation(), turn.turnNo(), failed));
  }

  /** A human is asked to look; nothing is sent. */
  private static TurnOutcome failed(RunningTurn turn, ModelResponse.Failure code) {
    StateRules.handoff(turn.conversation(), HandoffReason.ERROR);
    return TurnOutcome.failed("model call failed: " + code);
  }

  /** Classification, code-owned intents, the counters and the close they force, then the reply. */
  private TurnOutcome answerOpen(RunningTurn turn, Decision decision, List<Message> transcript) {
    decisions.classify(turn, decision);
    Optional<TurnOutcome> codeOwned = outcomes.codeOwned(turn, decision.leadIntent());
    if (codeOwned.isPresent()) {
      return codeOwned.get();
    }
    Optional<PreGuards.FixedClose> close =
        counters.count(turn.conversation(), decision, transcript);
    if (close.isPresent()) {
      return outcomes.fixedClose(turn, close.get().template(), close.get().reason());
    }
    decisions.applyEnding(turn, decision);
    return reply(turn, decision);
  }

  /**
   * The model's reply once its ending is applied. No reply before an end is a bad decision, handled
   * like output that is not one, though a handoff the decision asked for is kept.
   */
  private TurnOutcome reply(RunningTurn turn, Decision decision) {
    Conversation conversation = turn.conversation();
    if (decision.sendReply()) {
      return TurnOutcome.decided(decision.reply());
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

  /** At most one closing reply after an end; a reply over that is suppressed and flagged. */
  private TurnOutcome closingReply(RunningTurn turn, Decision decision) {
    Conversation conversation = turn.conversation();
    if (!decision.sendReply()) {
      return TurnOutcome.silent("model chose silence");
    }
    Map<String, Integer> checked =
        Map.of(
            "post_terminal_replies",
            conversation.postTerminalReplies,
            "chars",
            decision.reply().length());
    if (StateRules.closingReplyAllowed(conversation, decision.reply())) {
      trace.record(turn, TraceLog.Entry.guard("closing-reply", TraceOutcome.PASS, checked));
      return TurnOutcome.decided(decision.reply());
    }
    conversation.addFlag(Flag.LOOP_SUPPRESSED);
    trace.record(turn, TraceLog.Entry.guard("closing-reply", TraceOutcome.BLOCK, checked));
    return TurnOutcome.silent("closing reply suppressed");
  }

  /**
   * Anna's messages, the opener included, as assistant items and the lead's as user items, each cut
   * to {@code anna.sms.max-inbound-chars}: the model sees what the lead saw and said.
   */
  private List<ModelRequest.InputItem> input(List<Message> transcript) {
    return transcript.stream()
        .<ModelRequest.InputItem>map(
            message ->
                switch (message.direction()) {
                  case OUT -> new ModelRequest.AssistantText(message.text());
                  case IN -> new ModelRequest.UserText(cut(message.text(), sms.maxInboundChars()));
                })
        .toList();
  }

  /**
   * The lead's clock is in the zone fixed at start. Nothing books or offers slots: both are empty.
   */
  private TurnContext contextFor(RunningTurn turn) {
    Conversation conversation = turn.conversation();
    return new TurnContext(
        turn.lead(),
        turn.now().atZone(ZoneId.of(conversation.leadZoneId)),
        new TurnContext.Counters(
            conversation.agentTurns,
            conversation.objectionsUsed,
            conversation.offTopicStreak,
            conversation.postTerminalReplies),
        limits,
        null,
        List.of(),
        conversation.ended,
        conversation.endReason);
  }
}
