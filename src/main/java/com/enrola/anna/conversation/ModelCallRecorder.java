package com.enrola.anna.conversation;

import com.enrola.anna.agent.AgentService;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.PriceTable;
import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsDefaults;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records a running turn's model calls: a MODEL_CALL event for every round as it ends, priced from
 * the turn's price table, and for each call the turn's model columns, its cost the sum of its
 * rounds', with an ERROR event when it failed.
 */
@Component
class ModelCallRecorder {

  private final TraceLog trace;
  private final JsonMapper json;
  private final int maxRetries;

  ModelCallRecorder(TraceLog trace, JsonMapper json, SettingsDefaults defaults) {
    this.trace = trace;
    this.json = json;
    this.maxRetries = defaults.agent().maxRetries();
  }

  /** What the call returned for the turn row, beside what every call has. */
  private record Output(String decisionJson, String reasoningSummary) {}

  /**
   * The MODEL_CALL event of one round of {@code call}: what it cost and took, how it ended, and the
   * reasoning summary of a round that decided, which only the inspector shows.
   */
  void recordRound(
      RunningTurn turn, int round, ModelRoundPayload.Call call, ModelResponse response) {
    Settings settings = turn.settings();
    trace.record(
        turn,
        new TraceLog.Entry(
            TraceEventType.MODEL_CALL,
            "decide",
            TraceOutcome.INFO,
            new ModelRoundPayload(
                settings.agent().model(),
                settings.agent().reasoning().value(),
                round,
                call,
                response.usage(),
                cost(settings, response.usage()),
                response.latencyMs(),
                status(response),
                failure(response),
                summary(response))));
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
    return columns(turn, result, output);
  }

  private static BigDecimal cost(Settings settings, ModelResponse.Usage usage) {
    return new PriceTable(settings.priceTable()).cost(settings.agent().model(), usage);
  }

  private static ModelRoundPayload.Status status(ModelResponse response) {
    return switch (response) {
      case ModelResponse.Decided _ -> ModelRoundPayload.Status.DECIDED;
      case ModelResponse.ToolCalls _ -> ModelRoundPayload.Status.TOOL_CALLS;
      case ModelResponse.Failed _ -> ModelRoundPayload.Status.FAILED;
    };
  }

  private static ModelResponse.Failure failure(ModelResponse response) {
    return switch (response) {
      case ModelResponse.Failed failed -> failed.code();
      case ModelResponse.Decided _, ModelResponse.ToolCalls _ -> null;
    };
  }

  private static String summary(ModelResponse response) {
    return switch (response) {
      case ModelResponse.Decided decided -> decided.reasoningSummary();
      case ModelResponse.ToolCalls _, ModelResponse.Failed _ -> null;
    };
  }

  private TraceLog.Entry errorEvent(ModelResponse.Failed failed) {
    return new TraceLog.Entry(
        TraceEventType.ERROR,
        "model_call",
        TraceOutcome.INFO,
        new ErrorPayload(failed.code().name(), failed.detail(), maxAttempts(failed.code())));
  }

  /**
   * The client retries a transport failure, a timeout or an HTTP error, up to {@code
   * anna.agent.max-retries} times; a response that came back is never asked for again.
   */
  private int maxAttempts(ModelResponse.Failure failure) {
    return switch (failure) {
      case TIMEOUT, HTTP_5XX, HTTP_4XX, SDK -> 1 + maxRetries;
      case INCOMPLETE, FAILED, REFUSAL, NO_OUTPUT, SCHEMA, TOOL_ROUNDS -> 1;
    };
  }

  private Turn.ModelCall columns(RunningTurn turn, AgentService.TurnResult result, Output output) {
    Settings settings = turn.settings();
    ModelResponse.Usage usage = result.usage();
    return new Turn.ModelCall(
        output.decisionJson(),
        result.toolCalls().isEmpty() ? null : json.writeValueAsString(result.toolCalls()),
        settings.agent().model(),
        turn.prompt().id(),
        usage.in(),
        usage.cached(),
        usage.out(),
        usage.reasoning(),
        result.roundUsage().stream()
            .map(round -> cost(settings, round))
            .reduce(BigDecimal.ZERO, BigDecimal::add),
        Math.toIntExact(result.latencyMs()),
        output.reasoningSummary(),
        result.last().rawRequestJson(),
        result.last().rawResponseJson());
  }
}
