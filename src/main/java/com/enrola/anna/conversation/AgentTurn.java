package com.enrola.anna.conversation;

import com.enrola.anna.agent.AgentService;
import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.ModelRequest;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.TurnContext;
import com.enrola.anna.config.SmsProperties;
import com.enrola.anna.lead.Lead;
import com.enrola.anna.prompt.PromptLoader;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The model's part of a turn: asks the agent with the conversation's prompt bundle, the whole
 * transcript and the turn's context, records the call, and applies the decision. With no model,
 * when the call fails after the SDK's retries, or when the output is not a decision, a human is
 * asked to look, nothing is sent, and the conversation stays open.
 */
@Component
class AgentTurn {

  /** Limits the turn context reports to the model. */
  private static final TurnContext.Limits LIMITS = new TurnContext.Limits(15, 1, 3);

  private final AgentService agent;
  private final PromptLoader prompts;
  private final MessageRepository messages;
  private final ModelCallRecorder modelCalls;
  private final DecisionApplier decisions;
  private final SmsProperties sms;

  AgentTurn(
      AgentService agent,
      PromptLoader prompts,
      MessageRepository messages,
      ModelCallRecorder modelCalls,
      DecisionApplier decisions,
      SmsProperties sms) {
    this.agent = agent;
    this.prompts = prompts;
    this.messages = messages;
    this.modelCalls = modelCalls;
    this.decisions = decisions;
    this.sms = sms;
  }

  /** {@code text} cut to its first {@code maxChars} characters when it is longer. */
  static String cut(String text, int maxChars) {
    return text.length() > maxChars ? text.substring(0, maxChars) : text;
  }

  TurnOutcome run(Conversation conversation, Lead lead, int turnNo, Instant now) {
    if (!agent.available()) {
      StateRules.handoff(conversation, HandoffReason.ERROR);
      return TurnOutcome.silent("no model configured");
    }
    List<Message> transcript = messages.transcript(conversation.id);
    ModelResponse response =
        agent.decide(
            prompts.load(conversation.promptVersion),
            input(transcript),
            contextFor(conversation, lead, transcript, now));
    return switch (response) {
      case ModelResponse.Decided decided -> applied(conversation, turnNo, decided, now);
      case ModelResponse.Failed failed -> handedOff(conversation, turnNo, failed);
    };
  }

  /** The output as a decision, applied; output that is not one is a SCHEMA failure. */
  private TurnOutcome applied(
      Conversation conversation, int turnNo, ModelResponse.Decided decided, Instant now) {
    Decision decision;
    try {
      decision = decided.decision();
    } catch (ModelResponse.SchemaMismatch e) {
      return handedOff(
          conversation, turnNo, decided.failed(ModelResponse.Failure.SCHEMA, e.getMessage()));
    }
    Turn.ModelCall call = modelCalls.record(conversation, turnNo, decided);
    decisions.apply(conversation, turnNo, decision, now);
    return TurnOutcome.decided(decision.sendReply() ? decision.reply() : null, call);
  }

  private TurnOutcome handedOff(
      Conversation conversation, int turnNo, ModelResponse.Failed failed) {
    Turn.ModelCall call = modelCalls.record(conversation, turnNo, failed);
    StateRules.handoff(conversation, HandoffReason.ERROR);
    return TurnOutcome.failed("model call failed: " + failed.code(), call);
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
   * The lead's clock is in the zone fixed at start. Agent turns are the model's replies so far.
   * Code keeps no objection, off-topic or post-terminal counts, so those render as zero against the
   * limits, and there is no booking or offered slot to show.
   */
  private static TurnContext contextFor(
      Conversation conversation, Lead lead, List<Message> transcript, Instant now) {
    int agentTurns =
        (int) transcript.stream().filter(message -> message.source() == MessageSource.LLM).count();
    return new TurnContext(
        lead,
        now.atZone(ZoneId.of(conversation.leadZoneId)),
        new TurnContext.Counters(agentTurns, 0, 0, 0),
        LIMITS,
        null,
        List.of(),
        conversation.ended,
        conversation.endReason);
  }
}
