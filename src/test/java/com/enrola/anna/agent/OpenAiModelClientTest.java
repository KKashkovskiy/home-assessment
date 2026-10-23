package com.enrola.anna.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.config.AgentProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ObjectMappers;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.models.ErrorObject;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.Tool;
import com.openai.services.blocking.ResponseService;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The client against OpenAI faked as a JDK proxy: each request is kept for inspection and answered
 * with a scripted response, as the SDK would read it off the wire. Nothing leaves the machine.
 */
class OpenAiModelClientTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  static final String FAKE_KEY = "sk-test-not-a-real-key-4f2a";

  static final List<JsonNode> TOOLS =
      List.copyOf(
          JSON.readTree(
                  """
                  [{"type":"function","name":"get_availability","strict":true,"description":"slots",
                    "parameters":{"type":"object","additionalProperties":false,"required":["date_from"],
                                  "properties":{"date_from":{"type":"string"}}}},
                   {"type":"function","name":"book_call","strict":false,"description":"book",
                    "parameters":{"type":"object","additionalProperties":false,"required":["slot_id"],
                                  "properties":{"slot_id":{"type":"string"}}}}]
                  """)
              .values());

  static final String USAGE =
      """
      "usage": {"input_tokens": 100, "input_tokens_details": {"cached_tokens": 40},
                "output_tokens": 20, "output_tokens_details": {"reasoning_tokens": 5},
                "total_tokens": 120}
      """;

  static final String DECIDED =
      """
      "status": "completed",
      "output": [
        {"type": "reasoning", "id": "rs2", "summary": [{"type": "summary_text", "text": "Lead wants to save."}]},
        {"type": "message", "id": "m1", "role": "assistant", "status": "completed",
         "content": [{"type": "output_text", "text": "{\\"ok\\": true}", "annotations": []}]}
      ]
      """;

  static final String CALLS_AVAILABILITY =
      """
      "status": "completed",
      "output": [
        {"type": "reasoning", "id": "rs1", "summary": [], "encrypted_content": "opaque"},
        {"type": "function_call", "id": "fc1", "call_id": "c1", "name": "get_availability",
         "arguments": "{\\"date_from\\":\\"2026-07-27\\"}", "status": "completed"}
      ]
      """;

  final FakeOpenAi openAi = new FakeOpenAi();

  @Test
  void paramsAreStatelessAndStrict() {
    openAi.answer(response(DECIDED));

    openAi.client().call(request(List.of()));

    ResponseCreateParams params = openAi.requests.getFirst();
    assertThat(params.store()).contains(false);
    assertThat(params.previousResponseId()).isEmpty();
    assertThat(params.model().orElseThrow().asString()).isEqualTo("gpt-5.1");
    assertThat(params.maxOutputTokens()).contains(1200L);
    assertThat(params.reasoning().orElseThrow().effort().orElseThrow().asString()).isEqualTo("low");
    ResponseFormatTextJsonSchemaConfig format =
        params.text().orElseThrow().format().orElseThrow().asJsonSchema();
    assertThat(format.name()).isEqualTo("agent_decision");
    assertThat(format.strict()).contains(true);
    assertThat(format.schema()._additionalProperties().keySet())
        .containsExactlyInAnyOrder("type", "additionalProperties", "required", "properties");
    assertThat(params.tools()).isEmpty();
    assertThat(params.parallelToolCalls()).isEmpty();
  }

  @Test
  void transcriptKeepsOrderAndRoles() {
    openAi.answer(response(DECIDED));

    openAi.client().call(request(List.of()));

    List<EasyInputMessage> items =
        openAi.requests.getFirst().input().orElseThrow().asResponse().stream()
            .map(ResponseInputItem::asEasyInputMessage)
            .toList();
    assertThat(items)
        .extracting(EasyInputMessage::role)
        .containsExactly(
            EasyInputMessage.Role.ASSISTANT,
            EasyInputMessage.Role.USER,
            EasyInputMessage.Role.USER);
    assertThat(items.get(1).content().asTextInput()).isEqualTo("save money");
  }

  /** The inspector reads the instructions and the context item back from the stored request. */
  @Test
  void recordedRequestReadsBackAsThePromptItSent() {
    openAi.answer(response(DECIDED));

    ModelResponse response = openAi.client().call(request(List.of()));

    assertThat(OpenAiModelClient.sentPrompt(response.rawRequestJson()))
        .isEqualTo(new OpenAiModelClient.SentPrompt("You are Anna.", "### Context for this turn"));
  }

  /**
   * The real client with a key, against a port nothing listens on: the call fails, and the request
   * it recorded carries neither the key nor an Authorization header.
   */
  @Test
  void recordedRequestHoldsNoKey() {
    OpenAiModelClient client =
        new OpenAiModelClient(
            OpenAIOkHttpClient.builder()
                .apiKey(FAKE_KEY)
                .baseUrl("http://127.0.0.1:1")
                .maxRetries(0)
                .timeout(Duration.ofSeconds(2))
                .build());

    ModelResponse response = client.call(request(List.of()));

    assertThat(response).isInstanceOf(ModelResponse.Failed.class);
    assertThat(response.rawRequestJson())
        .contains("\"instructions\"")
        .doesNotContain(FAKE_KEY)
        .doesNotContainIgnoringCase("authorization")
        .doesNotContainIgnoringCase("bearer");
  }

  @Test
  void pingIsStrictWithoutReasoning() {
    openAi.answer(response(DECIDED));

    openAi.client().call(ModelRequest.ping("gpt-5.1"));

    ResponseCreateParams params = openAi.requests.getFirst();
    ResponseFormatTextJsonSchemaConfig format =
        params.text().orElseThrow().format().orElseThrow().asJsonSchema();
    assertThat(format.name()).isEqualTo("ping");
    assertThat(format.strict()).contains(true);
    assertThat(params.reasoning()).isEmpty();
    assertThat(params.store()).contains(false);
  }

  /** Strict whatever tools.json says, one call per round, reasoning returned for the next round. */
  @Test
  void toolsAreOfferedStrictOneCallAtATime() {
    openAi.answer(response(DECIDED));

    openAi.client().call(request(TOOLS));

    ResponseCreateParams params = openAi.requests.getFirst();
    List<FunctionTool> offered =
        params.tools().orElseThrow().stream().map(Tool::asFunction).toList();
    assertThat(offered)
        .extracting(FunctionTool::name)
        .containsExactly("get_availability", "book_call");
    assertThat(offered).allSatisfy(tool -> assertThat(tool.strict()).contains(true));
    assertThat(offered.getFirst().parameters().orElseThrow()._additionalProperties().keySet())
        .containsExactlyInAnyOrder("type", "additionalProperties", "required", "properties");
    assertThat(params.parallelToolCalls()).contains(false);
    assertThat(params.include().orElseThrow())
        .containsExactly(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
  }

  /**
   * A round that calls a tool comes back for the caller to answer, with what the next round must
   * replay: the reasoning unchanged and the call without its item id.
   */
  @Test
  void functionCallComesBackToBeAnswered() {
    openAi.answer(response(CALLS_AVAILABILITY));

    ModelResponse response = openAi.client().call(request(TOOLS));

    InputItem.FunctionCall call =
        new InputItem.FunctionCall("c1", "get_availability", "{\"date_from\":\"2026-07-27\"}");
    assertThat(openAi.requests).hasSize(1);
    assertThat(response)
        .isInstanceOfSatisfying(
            ModelResponse.ToolCalls.class,
            asked -> {
              assertThat(asked.calls()).containsExactly(call);
              assertThat(asked.replay()).hasSize(2).endsWith(call);
              assertThat(asked.replay().getFirst())
                  .isInstanceOfSatisfying(
                      InputItem.Opaque.class,
                      reasoning ->
                          assertThat(reasoning.raw().asReasoning().encryptedContent())
                              .contains("opaque"));
              assertThat(asked.usage()).isEqualTo(new ModelResponse.Usage(100, 40, 20, 5));
            });
  }

  /** The replayed items and the answer go out as the SDK's items, in order, with no item id. */
  @Test
  void answeredRoundIsSentBackInOrder() {
    openAi.answer(response(CALLS_AVAILABILITY)).answer(response(DECIDED));
    ModelResponse.ToolCalls asked = (ModelResponse.ToolCalls) openAi.client().call(request(TOOLS));
    List<InputItem> next = new ArrayList<>(request(TOOLS).input());
    next.addAll(asked.replay());
    next.add(new InputItem.FunctionOutput("c1", "slots!"));

    ModelResponse response = openAi.client().call(request(TOOLS).withInput(next));

    List<JsonNode> input =
        List.copyOf(JSON.readTree(response.rawRequestJson()).get("input").values());
    assertThat(input).hasSize(6);
    assertThat(input.get(3).get("type").stringValue()).isEqualTo("reasoning");
    assertThat(input.get(3).get("encrypted_content").stringValue()).isEqualTo("opaque");
    assertThat(input.get(4).get("type").stringValue()).isEqualTo("function_call");
    assertThat(input.get(4).get("call_id").stringValue()).isEqualTo("c1");
    assertThat(input.get(4).has("id")).isFalse();
    assertThat(input.get(5).get("type").stringValue()).isEqualTo("function_call_output");
    assertThat(input.get(5).get("call_id").stringValue()).isEqualTo("c1");
    assertThat(input.get(5).get("output").stringValue()).isEqualTo("slots!");
  }

  /** Responses as OpenAI sends them, read by the SDK's own mapper. */
  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          incomplete | "status": "incomplete", "incomplete_details": {"reason": "max_output_tokens"}, "output": [] | INCOMPLETE
          failed     | "status": "failed", "output": []                                                            | FAILED
          refusal    | "status": "completed", "output": [{"type": "message", "id": "m1", "role": "assistant", "status": "completed", "content": [{"type": "refusal", "refusal": "No."}]}] | REFUSAL
          no text    | "status": "completed", "output": []                                                         | NO_OUTPUT
          not JSON   | "status": "completed", "output": [{"type": "message", "id": "m1", "role": "assistant", "status": "completed", "content": [{"type": "output_text", "text": "{oops", "annotations": []}]}] | SCHEMA
          """)
  void unusableResponseIsAFailure(String name, String fields, ModelResponse.Failure code) {
    openAi.answer(response(fields));

    assertThat(openAi.client().call(request(List.of())))
        .isInstanceOfSatisfying(
            ModelResponse.Failed.class,
            failed -> {
              assertThat(failed.code()).isEqualTo(code);
              assertThat(failed.usage()).isEqualTo(new ModelResponse.Usage(100, 40, 20, 5));
            });
  }

  @Test
  void completedResponseIsDecidedWithUsageAndSummary() {
    openAi.answer(response(DECIDED));

    ModelResponse response = openAi.client().call(request(List.of()));

    assertThat(response)
        .isInstanceOfSatisfying(
            ModelResponse.Decided.class,
            decided -> {
              assertThat(decided.output().get("ok").booleanValue()).isTrue();
              assertThat(decided.reasoningSummary()).isEqualTo("Lead wants to save.");
              assertThat(decided.usage()).isEqualTo(new ModelResponse.Usage(100, 40, 20, 5));
              assertThat(decided.rawRequestJson()).contains("\"store\":false");
              assertThat(decided.rawResponseJson()).contains("\"cached_tokens\":40");
            });
  }

  @Test
  void httpErrorKeepsItsBodyAsJson() {
    openAi.answer(
        BadRequestException.builder()
            .headers(Headers.builder().build())
            .error(
                ErrorObject.builder()
                    .message("bad schema")
                    .type("invalid_request_error")
                    .code((String) null)
                    .param((String) null)
                    .build())
            .build());

    ModelResponse response = openAi.client().call(request(List.of()));

    assertThat(response)
        .isInstanceOfSatisfying(
            ModelResponse.Failed.class,
            failed -> {
              assertThat(failed.code()).isEqualTo(ModelResponse.Failure.HTTP_4XX);
              assertThat(JSON.readTree(failed.rawResponseJson()).isObject()).isTrue();
              assertThat(failed.rawResponseJson()).contains("bad schema");
            });
  }

  @Test
  void clientAndCheckExistOnlyWithAKey() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withBean(
                AgentProperties.class,
                () ->
                    new AgentProperties(
                        "gpt-5.1", null, null, Duration.ofSeconds(1), 0, 16, 4, false))
            .withUserConfiguration(
                OpenAiClientConfig.class, OpenAiModelClient.class, ModelCheck.class);

    runner
        .withPropertyValues("OPENAI_API_KEY=sk-test")
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(ModelClient.class)
                    .hasSingleBean(ModelCheck.class));
    runner
        .withPropertyValues("OPENAI_API_KEY= ")
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(OpenAIClient.class)
                    .doesNotHaveBean(ModelClient.class)
                    .doesNotHaveBean(ModelCheck.class));
  }

  private static ModelRequest request(List<JsonNode> tools) {
    return new ModelRequest(
        "gpt-5.1",
        "You are Anna.",
        List.of(
            new InputItem.AssistantText("Hi Lauren"),
            new InputItem.UserText("save money"),
            new InputItem.UserText("### Context for this turn")),
        "agent_decision",
        JSON.readTree(
            """
            {"type":"object","additionalProperties":false,"required":["reply"],
             "properties":{"reply":{"type":"string"}}}
            """),
        tools,
        "low",
        "auto",
        1200);
  }

  private static Response response(String fields) {
    String json =
        "{\"id\": \"r1\", \"object\": \"response\", \"created_at\": 0, \"model\": \"gpt-5.1\", "
            + fields
            + ", "
            + USAGE
            + "}";
    try {
      return ObjectMappers.jsonMapper().readValue(json, Response.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("not a response: " + json, e);
    }
  }

  /**
   * OpenAI with only {@code responses().create(params)} working: each request is kept and answered
   * with the next scripted response, or fails with the next scripted exception.
   */
  static final class FakeOpenAi {

    final Deque<Object> answers = new ArrayDeque<>();
    final List<ResponseCreateParams> requests = new ArrayList<>();

    FakeOpenAi answer(Object responseOrException) {
      answers.add(responseOrException);
      return this;
    }

    OpenAiModelClient client() {
      ResponseService responses =
          proxy(
              ResponseService.class,
              (method, args) -> {
                if (!method.equals("create")) {
                  throw new UnsupportedOperationException(method);
                }
                requests.add((ResponseCreateParams) args[0]);
                Object answer = answers.remove();
                if (answer instanceof RuntimeException exception) {
                  throw exception;
                }
                return answer;
              });
      return new OpenAiModelClient(
          proxy(
              OpenAIClient.class,
              (method, args) -> {
                if (!method.equals("responses")) {
                  throw new UnsupportedOperationException(method);
                }
                return responses;
              }));
    }
  }

  /** {@code answer} gets the method's name and arguments. */
  private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> answer) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (self, method, args) -> answer.apply(method.getName(), args)));
  }
}
