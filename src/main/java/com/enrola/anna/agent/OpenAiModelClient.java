package com.enrola.anna.agent;

import com.enrola.anna.agent.ModelResponse.Failure;
import com.enrola.anna.agent.ModelResponse.Usage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseTextConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Responses API over openai-java, stateless: {@code store} false, no previous response id, the
 * whole transcript supplied by the caller on every call, the output held to a strict JSON schema.
 * Exists only when {@code OPENAI_API_KEY} is set, over the shared {@link OpenAiClientConfig}
 * client; the key travels as a header and is never part of the recorded request JSON. The SDK
 * retries timeouts and 5xx; whatever is left after that becomes a {@link ModelResponse.Failed}.
 *
 * <p>The request's function tools are offered strict, one call at a time, and the calls are
 * answered here through the request's {@link ToolExecutor}, round after round, until the model
 * answers without one or the rounds run out.
 */
@Component
@ConditionalOnExpression(OpenAiClientConfig.WHEN_KEY_SET)
public final class OpenAiModelClient implements ModelClient {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private final OpenAIClient client;

  public OpenAiModelClient(OpenAIClient client) {
    this.client = client;
  }

  @Override
  public ModelResponse call(ModelRequest request) {
    Rounds rounds = new Rounds(request);
    Round round = rounds.send();
    for (int answered = 0;
        round instanceof Round.CallsTools calling && answered < request.tools().maxRounds();
        answered++) {
      rounds.answer(calling.response());
      round = rounds.send();
    }
    return switch (round) {
      case Round.Finished finished -> finished.response();
      case Round.CallsTools calling ->
          calling
              .trip()
              .failed(
                  Failure.TOOL_ROUNDS,
                  "still calling tools after " + request.tools().maxRounds() + " answered rounds");
    };
  }

  /** How one round ended: the call's response, or function calls that wait for answers. */
  private sealed interface Round {

    record Finished(ModelResponse response) implements Round {}

    /** {@code trip} is the call so far, for a failure if the calls are never answered. */
    record CallsTools(Response response, RoundTrip trip) implements Round {}
  }

  /**
   * One call's rounds. Each round sends the transcript and every earlier round's function calls
   * with their answers; with nothing stored server-side, the reasoning that led to a call goes back
   * too, as the encrypted content the request asked for. Usage and latency add up over the rounds.
   */
  private final class Rounds {

    private final ModelRequest request;
    private final List<ResponseInputItem> input;
    private final List<ToolCall> answered = new ArrayList<>();
    private final long start = System.nanoTime();
    private Usage used = Usage.ZERO;

    Rounds(ModelRequest request) {
      this.request = request;
      this.input =
          new ArrayList<>(request.input().stream().map(OpenAiModelClient::message).toList());
    }

    /** Sends the input so far; a failed round finishes the call. */
    Round send() {
      ResponseCreateParams params = params(request, input);
      String rawRequest = sdkJson(params._body());
      Response response;
      try {
        response = client.responses().create(params);
      } catch (OpenAIException e) {
        return new Round.Finished(failure(e, rawRequest));
      }
      used = used.plus(usage(response));
      RoundTrip trip = trip(rawRequest, sdkJson(response));
      return functionCalls(response).isEmpty()
          ? new Round.Finished(parse(response, trip))
          : new Round.CallsTools(response, trip);
    }

    /**
     * Answers {@code response}'s function calls and adds them, and what led to them, to the input.
     */
    void answer(Response response) {
      input.addAll(replayable(response));
      for (ResponseFunctionToolCall call : functionCalls(response)) {
        String result = request.tools().executor().execute(call.name(), call.arguments());
        answered.add(new ToolCall(call.name(), call.arguments(), result));
        input.add(
            ResponseInputItem.ofFunctionCallOutput(
                ResponseInputItem.FunctionCallOutput.builder()
                    .callId(call.callId())
                    .output(result)
                    .build()));
      }
    }

    /** What is left of an SDK error after its retries; an HTTP error's body is valid JSON. */
    private ModelResponse.Failed failure(OpenAIException error, String rawRequest) {
      return switch (error) {
        case OpenAIServiceException http ->
            trip(rawRequest, sdkJson(http.body()))
                .failed(
                    http.statusCode() >= 500 ? Failure.HTTP_5XX : Failure.HTTP_4XX,
                    "status " + http.statusCode());
        case OpenAIIoException io ->
            trip(rawRequest, null).failed(Failure.TIMEOUT, io.getClass().getSimpleName());
        default -> trip(rawRequest, null).failed(Failure.SDK, error.getClass().getSimpleName());
      };
    }

    private RoundTrip trip(String rawRequest, String rawResponse) {
      return new RoundTrip(
          used, millisSince(start), rawRequest, rawResponse, List.copyOf(answered));
    }
  }

  /** The SDK parameters for one round, {@code input} being everything the model is to see. */
  private static ResponseCreateParams params(ModelRequest request, List<ResponseInputItem> input) {
    ResponseCreateParams.Builder params =
        ResponseCreateParams.builder()
            .model(request.model())
            .instructions(request.instructions())
            .store(false)
            .maxOutputTokens(request.maxOutputTokens())
            .inputOfResponse(input)
            .text(strictJsonSchema(request.schemaName(), request.schema()));
    if (request.reasoningEffort() != null) {
      params.reasoning(reasoning(request.reasoningEffort(), request.reasoningSummary()));
    }
    List<JsonNode> tools = request.tools().definitions();
    if (!tools.isEmpty()) {
      params.parallelToolCalls(false).addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
      tools.forEach(definition -> params.addTool(functionTool(definition)));
    }
    return params.build();
  }

  /**
   * A completed response's last output text, parsed as JSON. A response that did not complete, a
   * refusal, no output text or text that is not JSON is a failure.
   */
  private static ModelResponse parse(Response response, RoundTrip trip) {
    Optional<ModelResponse.Failed> unfinished = unfinished(response, trip);
    if (unfinished.isPresent()) {
      return unfinished.get();
    }
    List<ResponseOutputMessage.Content> content = messageContent(response);
    Optional<String> refusal =
        content.stream()
            .filter(ResponseOutputMessage.Content::isRefusal)
            .map(part -> part.asRefusal().refusal())
            .findFirst();
    if (refusal.isPresent()) {
      return trip.failed(Failure.REFUSAL, refusal.get());
    }
    return content.stream()
        .filter(ResponseOutputMessage.Content::isOutputText)
        .map(part -> part.asOutputText().text())
        .reduce((first, last) -> last)
        .map(text -> output(text, reasoningSummary(response), trip))
        .orElseGet(() -> trip.failed(Failure.NO_OUTPUT, "no output text"));
  }

  /** A failure for any status but completed; a response without a status counts as completed. */
  private static Optional<ModelResponse.Failed> unfinished(Response response, RoundTrip trip) {
    return response
        .status()
        .flatMap(
            status ->
                switch (status.value()) {
                  case COMPLETED -> Optional.empty();
                  case INCOMPLETE ->
                      Optional.of(
                          trip.failed(
                              Failure.INCOMPLETE,
                              response.incompleteDetails().map(Object::toString).orElse("")));
                  case FAILED, CANCELLED, IN_PROGRESS, QUEUED, _UNKNOWN ->
                      Optional.of(trip.failed(Failure.FAILED, "status " + status));
                });
  }

  private static List<ResponseFunctionToolCall> functionCalls(Response response) {
    return response.output().stream()
        .filter(ResponseOutputItem::isFunctionCall)
        .map(ResponseOutputItem::asFunctionCall)
        .toList();
  }

  /**
   * What the next round must see again of a round that called tools: its reasoning and its function
   * calls, each call rebuilt without the item id, which names nothing when nothing is stored.
   */
  private static List<ResponseInputItem> replayable(Response response) {
    List<ResponseInputItem> items = new ArrayList<>();
    for (ResponseOutputItem item : response.output()) {
      if (item.isReasoning()) {
        items.add(ResponseInputItem.ofReasoning(item.asReasoning()));
      } else if (item.isFunctionCall()) {
        ResponseFunctionToolCall call = item.asFunctionCall();
        items.add(
            ResponseInputItem.ofFunctionCall(
                ResponseFunctionToolCall.builder()
                    .callId(call.callId())
                    .name(call.name())
                    .arguments(call.arguments())
                    .build()));
      }
    }
    return items;
  }

  private static ResponseInputItem message(ModelRequest.InputItem item) {
    EasyInputMessage.Role role =
        switch (item) {
          case ModelRequest.UserText _ -> EasyInputMessage.Role.USER;
          case ModelRequest.AssistantText _ -> EasyInputMessage.Role.ASSISTANT;
        };
    return ResponseInputItem.ofEasyInputMessage(
        EasyInputMessage.builder().role(role).content(item.text()).build());
  }

  /** The schema is sent as the caller gives it, not derived from Java types. */
  private static ResponseTextConfig strictJsonSchema(String name, JsonNode schema) {
    return ResponseTextConfig.builder()
        .format(
            ResponseFormatTextJsonSchemaConfig.builder()
                .name(name)
                .schema(
                    ResponseFormatTextJsonSchemaConfig.Schema.builder()
                        .additionalProperties(sdkProperties(schema))
                        .build())
                .strict(true)
                .build())
        .build();
  }

  /** One {@code tools.json} entry, strict like the output schema whatever the entry says. */
  private static FunctionTool functionTool(JsonNode definition) {
    return FunctionTool.builder()
        .name(definition.get("name").stringValue())
        .description(definition.get("description").stringValue())
        .parameters(
            FunctionTool.Parameters.builder()
                .additionalProperties(sdkProperties(definition.get("parameters")))
                .build())
        .strict(true)
        .build();
  }

  /** A JSON object's members as the SDK's untyped properties. */
  private static Map<String, JsonValue> sdkProperties(JsonNode object) {
    Map<String, JsonValue> properties = new LinkedHashMap<>();
    JSON.convertValue(object, OBJECT)
        .forEach((key, value) -> properties.put(key, JsonValue.from(value)));
    return properties;
  }

  private static Reasoning reasoning(String effort, String summary) {
    Reasoning.Builder reasoning = Reasoning.builder().effort(ReasoningEffort.of(effort));
    if (summary != null) {
      reasoning.summary(Reasoning.Summary.of(summary));
    }
    return reasoning.build();
  }

  private static ModelResponse output(String text, String reasoningSummary, RoundTrip trip) {
    try {
      return trip.decided(JSON.readTree(text), reasoningSummary);
    } catch (JacksonException e) {
      return trip.failed(Failure.SCHEMA, e.getOriginalMessage());
    }
  }

  private static List<ResponseOutputMessage.Content> messageContent(Response response) {
    return response.output().stream()
        .filter(ResponseOutputItem::isMessage)
        .flatMap(item -> item.asMessage().content().stream())
        .toList();
  }

  /** The model's reasoning summary lines joined, or null when it gave none. */
  private static String reasoningSummary(Response response) {
    String summary =
        response.output().stream()
            .filter(ResponseOutputItem::isReasoning)
            .flatMap(item -> item.asReasoning().summary().stream())
            .map(line -> line.text())
            .collect(Collectors.joining("\n"));
    return summary.isEmpty() ? null : summary;
  }

  private static Usage usage(Response response) {
    return response
        .usage()
        .map(
            used ->
                new Usage(
                    Math.toIntExact(used.inputTokens()),
                    Math.toIntExact(used.inputTokensDetails().cachedTokens()),
                    Math.toIntExact(used.outputTokens()),
                    Math.toIntExact(used.outputTokensDetails().reasoningTokens())))
        .orElse(Usage.ZERO);
  }

  /** The SDK's own Jackson for its own types; a failure here is a bug, not a model failure. */
  private static String sdkJson(Object sdkValue) {
    try {
      return ObjectMappers.jsonMapper().writeValueAsString(sdkValue);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("SDK value not serialisable", e);
    }
  }

  private static long millisSince(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  /**
   * What every outcome of a call shares: usage and latency so far, the latest round's raw JSON both
   * ways, and the function calls answered on the way.
   */
  private record RoundTrip(
      Usage usage,
      long latencyMs,
      String rawRequest,
      String rawResponse,
      List<ToolCall> toolCalls) {

    ModelResponse.Failed failed(Failure code, String detail) {
      return new ModelResponse.Failed(
          code, detail, usage, latencyMs, rawRequest, rawResponse, toolCalls);
    }

    ModelResponse.Decided decided(JsonNode output, String reasoningSummary) {
      return new ModelResponse.Decided(
          output, reasoningSummary, usage, latencyMs, rawRequest, rawResponse, toolCalls);
    }
  }
}
