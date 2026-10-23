package com.enrola.anna.conversation;

import com.enrola.anna.agent.AgentService;
import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.ModelRequest;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.TurnContext;
import com.enrola.anna.config.LimitsProperties;
import com.enrola.anna.config.SmsProperties;
import com.enrola.anna.prompt.PromptLoader;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * One recorded model call for a running turn: the conversation's prompt bundle, the whole
 * transcript and the turn's context go in; a decision or a failure comes out, with the turn's model
 * columns either way. Output that is not a decision is recorded as a SCHEMA failure.
 */
@Component
class AgentCall {

  private final AgentService agent;
  private final PromptLoader prompts;
  private final MessageRepository messages;
  private final ModelCallRecorder modelCalls;
  private final LimitsProperties limits;
  private final SmsProperties sms;

  AgentCall(
      AgentService agent,
      PromptLoader prompts,
      MessageRepository messages,
      ModelCallRecorder modelCalls,
      LimitsProperties limits,
      SmsProperties sms) {
    this.agent = agent;
    this.prompts = prompts;
    this.messages = messages;
    this.modelCalls = modelCalls;
    this.limits = limits;
    this.sms = sms;
  }

  /** What the model is asked: the transcript and the turn's context, as of the turn's start. */
  record Question(List<Message> transcript, TurnContext context) {

    /** The same question with a note on what the rejected reply broke. */
    Question withRepairNote(String note) {
      return new Question(transcript, context.withRepairNote(note));
    }
  }

  /** What one recorded call came to, with the turn's model columns either way. */
  sealed interface Answer {
    Turn.ModelCall call();

    record Decided(Decision decision, Turn.ModelCall call) implements Answer {}

    record Failed(ModelResponse.Failure code, Turn.ModelCall call) implements Answer {}
  }

  /** {@code text} cut to its first {@code maxChars} characters when it is longer. */
  static String cut(String text, int maxChars) {
    return text.length() > maxChars ? text.substring(0, maxChars) : text;
  }

  Question questionFor(RunningTurn turn) {
    return new Question(messages.transcript(turn.conversation().id), contextFor(turn));
  }

  Answer ask(RunningTurn turn, Question question) {
    ModelResponse response =
        agent.decide(
            prompts.load(turn.conversation().promptVersion),
            input(question.transcript()),
            question.context());
    return switch (response) {
      case ModelResponse.Decided decided -> decisionOf(turn, decided);
      case ModelResponse.Failed failed -> failure(turn, failed);
    };
  }

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
        conversation.endReason,
        null);
  }
}
