package com.enrola.anna.conversation;

import com.enrola.anna.agent.AgentService;
import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.Flag;
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
 * transcript and the turn's context go in, the model's function calls are answered by {@link
 * TurnToolCalls}, and a decision or a failure comes out, with the turn's model columns either way.
 * Output that is not a decision is recorded as a SCHEMA failure.
 */
@Component
class AgentCall {

  private final AgentService agent;
  private final PromptLoader prompts;
  private final MessageRepository messages;
  private final ModelCallRecorder modelCalls;
  private final TurnToolCalls tools;
  private final LimitsProperties limits;
  private final SmsProperties sms;

  AgentCall(
      AgentService agent,
      PromptLoader prompts,
      MessageRepository messages,
      ModelCallRecorder modelCalls,
      TurnToolCalls tools,
      LimitsProperties limits,
      SmsProperties sms) {
    this.agent = agent;
    this.prompts = prompts;
    this.messages = messages;
    this.modelCalls = modelCalls;
    this.tools = tools;
    this.limits = limits;
    this.sms = sms;
  }

  /** What the model is asked: the transcript and the turn's context, as of the turn's start. */
  record Question(List<Message> transcript, TurnContext context) {}

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

  /**
   * {@code asked} again with a note on what the rejected reply broke, and the offer as it stands
   * now: this turn's tool calls may have replaced the one the question started with.
   */
  Question repairOf(RunningTurn turn, Question asked, String note) {
    return new Question(
        asked.transcript(),
        asked.context().withRepair(note, turn.conversation().lastOfferedSlots()));
  }

  /** A call that may use the scheduling tools, answered for this turn by {@link TurnToolCalls}. */
  Answer ask(RunningTurn turn, Question question) {
    return answerOf(
        turn,
        agent.decide(
            prompts.load(turn.conversation().promptVersion),
            input(question.transcript()),
            question.context(),
            (name, argumentsJson) -> tools.answer(turn, name, argumentsJson)));
  }

  /** A call with no tools, after an end, when the model only classifies the message. */
  Answer classify(RunningTurn turn, Question question) {
    return answerOf(
        turn,
        agent.classify(
            prompts.load(turn.conversation().promptVersion),
            input(question.transcript()),
            question.context()));
  }

  private Answer answerOf(RunningTurn turn, ModelResponse response) {
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

  /** The lead's clock is in the zone fixed at start; book_call books nothing, so no booking. */
  private TurnContext contextFor(RunningTurn turn) {
    Conversation conversation = turn.conversation();
    return new TurnContext(
        turn.lead(),
        turn.now().atZone(ZoneId.of(conversation.leadZoneId)),
        conversation.flags().contains(Flag.TZ_ASSUMED),
        new TurnContext.Counters(
            conversation.agentTurns,
            conversation.objectionsUsed,
            conversation.offTopicStreak,
            conversation.postTerminalReplies),
        limits,
        null,
        conversation.lastOfferedSlots(),
        conversation.ended,
        conversation.endReason,
        null);
  }
}
