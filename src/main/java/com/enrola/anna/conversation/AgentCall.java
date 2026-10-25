package com.enrola.anna.conversation;

import com.enrola.anna.agent.AgentService;
import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.ToolLoopCallbacks;
import com.enrola.anna.config.Settings;
import org.springframework.stereotype.Component;

/**
 * One recorded model call for a running turn: the turn's pinned prompt and a {@link
 * TurnQuestions.Question} go in, each round is traced as it ends, the model's function calls are
 * answered by {@link TurnToolCalls} while the turn has tool rounds left, and a decision or a
 * failure comes out, with the turn's model columns either way. Output that is not a decision is
 * recorded as a SCHEMA failure.
 */
@Component
class AgentCall {

  private final AgentService agent;
  private final TurnQuestions questions;
  private final ModelCallRecorder modelCalls;
  private final TurnToolCalls tools;

  AgentCall(
      AgentService agent,
      TurnQuestions questions,
      ModelCallRecorder modelCalls,
      TurnToolCalls tools) {
    this.agent = agent;
    this.questions = questions;
    this.modelCalls = modelCalls;
    this.tools = tools;
  }

  /** What one recorded call came to, with the turn's model columns either way. */
  sealed interface Answer {
    Turn.ModelCall call();

    record Decided(Decision decision, Turn.ModelCall call) implements Answer {}

    record Failed(ModelResponse.Failure code, Turn.ModelCall call) implements Answer {}
  }

  /** The tool rounds of {@code turn}, its settings' {@code maxToolRounds} for all its calls. */
  ToolRounds toolRoundsFor(RunningTurn turn) {
    return new ToolRounds(turn, turn.settings().limits().maxToolRounds());
  }

  /** A call that may use the scheduling tools while {@code rounds} has some left. */
  Answer ask(RunningTurn turn, TurnQuestions.Question question, ToolRounds rounds) {
    return answerOf(turn, agent.decide(callFor(turn, question), rounds));
  }

  /**
   * A call with no tools, for after an end: nothing may be scheduled then, so {@code rounds} grants
   * no tool round from here on.
   */
  Answer classify(RunningTurn turn, TurnQuestions.Question question, ToolRounds rounds) {
    rounds.endToolRounds();
    return answerOf(turn, agent.classify(callFor(turn, question), rounds));
  }

  /**
   * The one repair of a reply the guards rejected, its rounds traced as the repair's: with the
   * tools {@code rounds} has left, or none when the decision ended the conversation.
   */
  Answer repair(RunningTurn turn, TurnQuestions.Question question, ToolRounds rounds) {
    rounds.startRepair();
    return turn.conversation().ended
        ? classify(turn, question, rounds)
        : ask(turn, question, rounds);
  }

  private AgentService.Call callFor(RunningTurn turn, TurnQuestions.Question question) {
    Settings settings = turn.settings();
    return new AgentService.Call(
        turn.prompt(),
        settings.agent(),
        settings.persona(),
        settings.limits(),
        questions.transcriptOf(question, settings.limits()),
        question.context());
  }

  private Answer answerOf(RunningTurn turn, AgentService.TurnResult result) {
    return switch (result.last()) {
      case ModelResponse.Decided decided -> decisionOf(turn, result, decided);
      case ModelResponse.Failed failed -> failure(turn, result, failed);
    };
  }

  private Answer decisionOf(
      RunningTurn turn, AgentService.TurnResult result, ModelResponse.Decided decided) {
    Decision decision;
    try {
      decision = decided.decision();
    } catch (ModelResponse.SchemaMismatch e) {
      ModelResponse.Failed failed = decided.failed(ModelResponse.Failure.SCHEMA, e.getMessage());
      return failure(turn, result.withLast(failed), failed);
    }
    return new Answer.Decided(decision, modelCalls.record(turn, result));
  }

  private Answer failure(
      RunningTurn turn, AgentService.TurnResult result, ModelResponse.Failed failed) {
    return new Answer.Failed(failed.code(), modelCalls.record(turn, result));
  }

  /**
   * A turn's side of its calls' tool loops. Each round is traced as it ends, numbered across the
   * turn so a repair's rounds follow the decision's, and marked with the call it belongs to. Rounds
   * are granted while any are left, and {@link TurnToolCalls} answers the calls. The decision call
   * and its repair share one.
   */
  final class ToolRounds implements ToolLoopCallbacks {

    private final RunningTurn turn;
    private int toolRoundsLeft;
    private int roundsEnded;
    private ModelRoundPayload.Call call = ModelRoundPayload.Call.DECIDE;

    private ToolRounds(RunningTurn turn, int toolRounds) {
      this.turn = turn;
      this.toolRoundsLeft = toolRounds;
    }

    private void startRepair() {
      call = ModelRoundPayload.Call.REPAIR;
    }

    private void endToolRounds() {
      toolRoundsLeft = 0;
    }

    @Override
    public void roundEnded(ModelResponse response) {
      roundsEnded++;
      modelCalls.recordRound(turn, roundsEnded, call, response);
    }

    @Override
    public boolean grantToolRound() {
      if (toolRoundsLeft == 0) {
        return false;
      }
      toolRoundsLeft--;
      return true;
    }

    @Override
    public String answer(String name, String argumentsJson) {
      return tools.answer(turn, name, argumentsJson);
    }
  }
}
