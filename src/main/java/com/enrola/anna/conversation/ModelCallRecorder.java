package com.enrola.anna.conversation;

import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.PriceTable;
import com.enrola.anna.config.AgentProperties;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records one model call: the turn's model columns, priced from {@link PriceTable}, the function
 * calls answered on the way, and its trace events, a MODEL_CALL for every call and an ERROR for a
 * failed one. Each function call has its own TOOL_CALL event, written as it ran.
 */
@Component
class ModelCallRecorder {

  private final TraceLog trace;
  private final PriceTable prices;
  private final AgentProperties agent;
  private final JsonMapper json;

  ModelCallRecorder(TraceLog trace, PriceTable prices, AgentProperties agent, JsonMapper json) {
    this.trace = trace;
    this.prices = prices;
    this.agent = agent;
    this.json = json;
  }

  /** What the call returned for the turn row, beside what every call has. */
  private record Output(String decisionJson, String reasoningSummary) {}

  /** Writes the trace events and returns the columns for the turn row. */
  Turn.ModelCall record(Conversation conversation, int turnNo, ModelResponse response) {
    BigDecimal cost = prices.cost(agent.model(), response.usage());
    return switch (response) {
      case ModelResponse.Decided decided -> {
        trace.record(conversation.id, turnNo, modelCallEvent(response, cost, "DECIDED"));
        Output output = new Output(decided.output().toString(), decided.reasoningSummary());
        yield columns(conversation.promptVersion, response, cost, output);
      }
      case ModelResponse.Failed failed -> {
        trace.record(conversation.id, turnNo, modelCallEvent(response, cost, failed.code().name()));
        trace.record(conversation.id, turnNo, errorEvent(failed));
        yield columns(conversation.promptVersion, response, cost, new Output(null, null));
      }
    };
  }

  private TraceLog.Entry modelCallEvent(ModelResponse response, BigDecimal cost, String status) {
    return new TraceLog.Entry(
        TraceEventType.MODEL_CALL,
        "decide",
        TraceOutcome.INFO,
        Map.of(
            "model", agent.model(),
            "effort", Objects.requireNonNullElse(agent.reasoningEffort(), "default"),
            "tokens", response.usage(),
            "cost_usd", cost,
            "latency_ms", response.latencyMs(),
            "status", status));
  }

  private static TraceLog.Entry errorEvent(ModelResponse.Failed failed) {
    return new TraceLog.Entry(
        TraceEventType.ERROR,
        "model_call",
        TraceOutcome.INFO,
        Map.of(
            "code", failed.code().name(),
            "detail", Objects.requireNonNullElse(failed.detail(), "")));
  }

  private Turn.ModelCall columns(
      String promptVersion, ModelResponse response, BigDecimal cost, Output output) {
    ModelResponse.Usage usage = response.usage();
    return new Turn.ModelCall(
        output.decisionJson(),
        response.toolCalls().isEmpty() ? null : json.writeValueAsString(response.toolCalls()),
        agent.model(),
        promptVersion,
        usage.in(),
        usage.cached(),
        usage.out(),
        usage.reasoning(),
        cost,
        Math.toIntExact(response.latencyMs()),
        output.reasoningSummary(),
        response.rawRequestJson(),
        response.rawResponseJson());
  }
}
