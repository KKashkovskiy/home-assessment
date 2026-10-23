package com.enrola.anna.conversation;

import com.enrola.anna.agent.AgentService;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.PriceTable;
import com.enrola.anna.config.AgentProperties;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records a running turn's model calls: a MODEL_CALL event for every round as it ends, priced from
 * {@link PriceTable}, and for each call the turn's model columns, its cost the sum of its rounds',
 * with an ERROR event when it failed.
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

  /** The MODEL_CALL event of one round: what it cost and took, and how it ended. */
  void recordRound(RunningTurn turn, int round, ModelResponse response) {
    trace.record(
        turn,
        new TraceLog.Entry(
            TraceEventType.MODEL_CALL,
            "decide",
            TraceOutcome.INFO,
            Map.of(
                "model", agent.model(),
                "effort", Objects.requireNonNullElse(agent.reasoningEffort(), "default"),
                "round", round,
                "tokens", response.usage(),
                "cost_usd", cost(response.usage()),
                "latency_ms", response.latencyMs(),
                "status", status(response))));
  }

  /** Writes the ERROR event of a failed call and returns the columns for the turn row. */
  Turn.ModelCall record(RunningTurn turn, AgentService.TurnResult result) {
    Output output =
        switch (result.last()) {
          case ModelResponse.Decided decided ->
              new Output(decided.output().toString(), decided.reasoningSummary());
          case ModelResponse.Failed failed -> {
            trace.record(turn, errorEvent(failed));
            yield new Output(null, null);
          }
        };
    return columns(turn.conversation().promptVersion, result, output);
  }

  private BigDecimal cost(ModelResponse.Usage usage) {
    return prices.cost(agent.model(), usage);
  }

  private static String status(ModelResponse response) {
    return switch (response) {
      case ModelResponse.Decided _ -> "DECIDED";
      case ModelResponse.ToolCalls _ -> "TOOL_CALLS";
      case ModelResponse.Failed failed -> failed.code().name();
    };
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
      String promptVersion, AgentService.TurnResult result, Output output) {
    ModelResponse.Usage usage = result.usage();
    return new Turn.ModelCall(
        output.decisionJson(),
        result.toolCalls().isEmpty() ? null : json.writeValueAsString(result.toolCalls()),
        agent.model(),
        promptVersion,
        usage.in(),
        usage.cached(),
        usage.out(),
        usage.reasoning(),
        result.roundUsage().stream().map(this::cost).reduce(BigDecimal.ZERO, BigDecimal::add),
        Math.toIntExact(result.latencyMs()),
        output.reasoningSummary(),
        result.last().rawRequestJson(),
        result.last().rawResponseJson());
  }
}
