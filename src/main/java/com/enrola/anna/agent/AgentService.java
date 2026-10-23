package com.enrola.anna.agent;

import com.enrola.anna.agent.ModelResponse.Usage;
import com.enrola.anna.config.AgentProperties;
import com.enrola.anna.config.PersonaProperties;
import com.enrola.anna.config.SmsProperties;
import com.enrola.anna.prompt.PromptBundle;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Runs the model's part of a turn: model, tools, model, until the model answers, a round fails, or
 * it still calls tools when the turn has no tool round left. Builds the request from the bundle,
 * the transcript and the turn's context; the caller's {@link ToolLoopCallbacks} records each round,
 * grants the tool rounds and answers the calls, and the caller maps the output to a {@link
 * Decision} and applies it. Without {@code OPENAI_API_KEY} there is no client and {@link
 * #available()} is false.
 */
@Service
public class AgentService {

  private static final Logger log = LoggerFactory.getLogger(AgentService.class);

  private final ModelClient client;
  private final AgentProperties agent;
  private final PersonaProperties persona;
  private final SmsProperties sms;

  public AgentService(
      ObjectProvider<ModelClient> client,
      AgentProperties agent,
      PersonaProperties persona,
      SmsProperties sms) {
    this.client = client.getIfAvailable();
    this.agent = agent;
    this.persona = persona;
    this.sms = sms;
    if (this.client == null) {
      log.warn("model check skipped: OPENAI_API_KEY not set; inbound turns hand off with ERROR");
    }
  }

  /**
   * What one call came to: how its last round ended, the function calls answered on the way, each
   * round's usage in order, and the latency of them all. A call that ran out of tool rounds ends
   * {@link ModelResponse.Failure#TOOL_ROUNDS}, with the raw JSON of the round that still called
   * tools.
   */
  public record TurnResult(
      ModelResponse.Final last, List<ToolCall> toolCalls, List<Usage> roundUsage, long latencyMs) {

    public TurnResult {
      toolCalls = List.copyOf(toolCalls);
      roundUsage = List.copyOf(roundUsage);
    }

    /** Every round's usage summed. */
    public Usage usage() {
      return roundUsage.stream().reduce(Usage.ZERO, Usage::plus);
    }

    /** This call with {@code replacement} as its last round, as when its output is unusable. */
    public TurnResult withLast(ModelResponse.Final replacement) {
      return new TurnResult(replacement, toolCalls, roundUsage, latencyMs);
    }
  }

  public boolean available() {
    return client != null;
  }

  /**
   * The decision call: {@code transcript} replayed whole, then {@code context} as the last item,
   * with the bundle's tools offered.
   */
  public TurnResult decide(
      PromptBundle bundle,
      List<InputItem> transcript,
      TurnContext context,
      ToolLoopCallbacks callbacks) {
    return run(request(bundle, transcript, context, bundle.tools()), callbacks);
  }

  /** Like {@link #decide}, with no tools offered: after an end the model only classifies. */
  public TurnResult classify(
      PromptBundle bundle,
      List<InputItem> transcript,
      TurnContext context,
      ToolLoopCallbacks callbacks) {
    return run(request(bundle, transcript, context, List.of()), callbacks);
  }

  private TurnResult run(ModelRequest request, ToolLoopCallbacks callbacks) {
    Exchange exchange = new Exchange(request, callbacks);
    ModelResponse response = exchange.send();
    while (response instanceof ModelResponse.ToolCalls asked && callbacks.grantToolRound()) {
      exchange.answer(asked);
      response = exchange.send();
    }
    return switch (response) {
      case ModelResponse.Final last -> exchange.result(last);
      case ModelResponse.ToolCalls asked ->
          exchange.result(
              asked.failed(ModelResponse.Failure.TOOL_ROUNDS, "still calling tools, none left"));
    };
  }

  private ModelRequest request(
      PromptBundle bundle, List<InputItem> transcript, TurnContext context, List<JsonNode> tools) {
    List<InputItem> input = new ArrayList<>(transcript);
    input.add(new InputItem.UserText(context.render()));
    return new ModelRequest(
        agent.model(),
        bundle.render(persona, sms),
        input,
        bundle.schema().get("name").stringValue(),
        bundle.schema().get("schema"),
        tools,
        agent.reasoningEffort(),
        agent.reasoningSummary(),
        agent.maxOutputTokens());
  }

  /**
   * One call's rounds. Each round sends the request's input followed by every earlier round's
   * replayed items and answers: nothing is stored server-side, so the model sees it all again.
   */
  private final class Exchange {

    private final ModelRequest request;
    private final ToolLoopCallbacks callbacks;
    private final List<InputItem> input;
    private final List<ToolCall> answered = new ArrayList<>();
    private final List<Usage> roundUsage = new ArrayList<>();
    private long latencyMs;

    Exchange(ModelRequest request, ToolLoopCallbacks callbacks) {
      this.request = request;
      this.callbacks = callbacks;
      this.input = new ArrayList<>(request.input());
    }

    /** Sends the input so far as the next round and reports the round's end. */
    ModelResponse send() {
      ModelResponse response = client.call(request.withInput(input));
      roundUsage.add(response.usage());
      latencyMs += response.latencyMs();
      callbacks.roundEnded(response);
      return response;
    }

    /** Answers {@code asked}'s function calls and adds them, and what led to them, to the input. */
    void answer(ModelResponse.ToolCalls asked) {
      input.addAll(asked.replay());
      for (InputItem.FunctionCall call : asked.calls()) {
        String result = callbacks.answer(call.name(), call.argumentsJson());
        answered.add(new ToolCall(call.name(), call.argumentsJson(), result));
        input.add(new InputItem.FunctionOutput(call.callId(), result));
      }
    }

    TurnResult result(ModelResponse.Final last) {
      return new TurnResult(last, answered, roundUsage, latencyMs);
    }
  }
}
