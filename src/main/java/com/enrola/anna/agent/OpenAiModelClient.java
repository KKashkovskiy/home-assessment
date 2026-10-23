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
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseTextConfig;
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
    ResponseCreateParams params = params(request);
    String rawRequest = sdkJson(params._body());
    long start = System.nanoTime();
    try {
      Response response = client.responses().create(params);
      return parse(response, rawRequest, millisSince(start));
    } catch (OpenAIServiceException e) {
      return serviceFailure(e, rawRequest, millisSince(start));
    } catch (OpenAIIoException e) {
      return new RoundTrip(Usage.ZERO, millisSince(start), rawRequest, null)
          .failed(Failure.TIMEOUT, e.getClass().getSimpleName());
    } catch (OpenAIException e) {
      return new RoundTrip(Usage.ZERO, millisSince(start), rawRequest, null)
          .failed(Failure.SDK, e.getClass().getSimpleName());
    }
  }

  /** The SDK parameters for one request; package-private so a test can inspect them offline. */
  static ResponseCreateParams params(ModelRequest request) {
    ResponseCreateParams.Builder params =
        ResponseCreateParams.builder()
            .model(request.model())
            .instructions(request.instructions())
            .store(false)
            .maxOutputTokens(request.maxOutputTokens())
            .inputOfResponse(request.input().stream().map(OpenAiModelClient::message).toList())
            .text(strictJsonSchema(request.schemaName(), request.schema()));
    if (request.reasoningEffort() != null) {
      params.reasoning(reasoning(request.reasoningEffort(), request.reasoningSummary()));
    }
    return params.build();
  }

  /**
   * A completed response's last output text, parsed as JSON. A response that did not complete, a
   * refusal, no output text or text that is not JSON is a failure. Package-private for the test.
   */
  static ModelResponse parse(Response response, String rawRequest, long latencyMs) {
    RoundTrip trip = new RoundTrip(usage(response), latencyMs, rawRequest, sdkJson(response));
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

  /** The HTTP error as a failure; its body, valid JSON, is the recorded response. */
  static ModelResponse.Failed serviceFailure(
      OpenAIServiceException e, String rawRequest, long latencyMs) {
    Failure code = e.statusCode() >= 500 ? Failure.HTTP_5XX : Failure.HTTP_4XX;
    return new RoundTrip(Usage.ZERO, latencyMs, rawRequest, sdkJson(e.body()))
        .failed(code, "status " + e.statusCode());
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
    ResponseFormatTextJsonSchemaConfig.Schema.Builder sdkSchema =
        ResponseFormatTextJsonSchemaConfig.Schema.builder();
    JSON.convertValue(schema, OBJECT)
        .forEach((key, value) -> sdkSchema.putAdditionalProperty(key, JsonValue.from(value)));
    return ResponseTextConfig.builder()
        .format(
            ResponseFormatTextJsonSchemaConfig.builder()
                .name(name)
                .schema(sdkSchema.build())
                .strict(true)
                .build())
        .build();
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

  /** What every outcome of one call shares: usage, latency and the raw JSON both ways. */
  private record RoundTrip(Usage usage, long latencyMs, String rawRequest, String rawResponse) {

    ModelResponse.Failed failed(Failure code, String detail) {
      return new ModelResponse.Failed(code, detail, usage, latencyMs, rawRequest, rawResponse);
    }

    ModelResponse.Decided decided(JsonNode output, String reasoningSummary) {
      return new ModelResponse.Decided(
          output, reasoningSummary, usage, latencyMs, rawRequest, rawResponse);
    }
  }
}
