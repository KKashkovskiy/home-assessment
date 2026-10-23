package com.enrola.anna.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.config.AgentProperties;
import com.openai.client.OpenAIClient;
import com.openai.core.ObjectMappers;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.models.ErrorObject;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseInputItem;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/** The request the client would send, built and inspected with no network. */
class OpenAiModelClientTest {

  static final ModelRequest DECISION_REQUEST =
      new ModelRequest(
          "gpt-5.1",
          "You are Anna.",
          List.of(
              new ModelRequest.AssistantText("Hi Lauren"),
              new ModelRequest.UserText("save money"),
              new ModelRequest.UserText("### Context for this turn")),
          "agent_decision",
          JsonMapper.builder()
              .build()
              .readTree(
                  """
                  {"type":"object","additionalProperties":false,"required":["reply"],
                   "properties":{"reply":{"type":"string"}}}
                  """),
          "low",
          "auto",
          1200);

  @Test
  void paramsAreStatelessAndStrict() throws Exception {
    ResponseCreateParams params = OpenAiModelClient.params(DECISION_REQUEST);

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
    assertThat(ObjectMappers.jsonMapper().writeValueAsString(params._body()))
        .contains("\"store\":false");
  }

  @Test
  void transcriptKeepsOrderAndRoles() {
    List<EasyInputMessage> items =
        OpenAiModelClient.params(DECISION_REQUEST).input().orElseThrow().asResponse().stream()
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

  @Test
  void pingIsStrictWithoutReasoning() {
    ResponseCreateParams params = OpenAiModelClient.params(ModelRequest.ping("gpt-5.1"));

    ResponseFormatTextJsonSchemaConfig format =
        params.text().orElseThrow().format().orElseThrow().asJsonSchema();
    assertThat(format.name()).isEqualTo("ping");
    assertThat(format.strict()).contains(true);
    assertThat(params.reasoning()).isEmpty();
    assertThat(params.store()).contains(false);
  }

  static final String USAGE =
      """
      "usage": {"input_tokens": 100, "input_tokens_details": {"cached_tokens": 40},
                "output_tokens": 20, "output_tokens_details": {"reasoning_tokens": 5},
                "total_tokens": 120}
      """;

  /** Responses as OpenAI sends them, read by the SDK's own mapper: no network. */
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
  void unusableResponseIsAFailure(String name, String fields, ModelResponse.Failure code)
      throws Exception {
    ModelResponse parsed = OpenAiModelClient.parse(response(fields), "{}", 7);

    assertThat(parsed)
        .isInstanceOfSatisfying(
            ModelResponse.Failed.class,
            failed -> {
              assertThat(failed.code()).isEqualTo(code);
              assertThat(failed.usage()).isEqualTo(new ModelResponse.Usage(100, 40, 20, 5));
            });
  }

  @Test
  void completedResponseIsDecidedWithUsageAndSummary() throws Exception {
    Response response =
        response(
            """
            "status": "completed",
            "output": [
              {"type": "reasoning", "id": "rs1", "summary": [{"type": "summary_text", "text": "Lead wants to save."}]},
              {"type": "message", "id": "m1", "role": "assistant", "status": "completed",
               "content": [{"type": "output_text", "text": "{\\"ok\\": true}", "annotations": []}]}
            ]
            """);

    ModelResponse parsed = OpenAiModelClient.parse(response, "{\"req\":1}", 7);

    assertThat(parsed)
        .isInstanceOfSatisfying(
            ModelResponse.Decided.class,
            decided -> {
              assertThat(decided.output().get("ok").booleanValue()).isTrue();
              assertThat(decided.reasoningSummary()).isEqualTo("Lead wants to save.");
              assertThat(decided.usage()).isEqualTo(new ModelResponse.Usage(100, 40, 20, 5));
              assertThat(decided.latencyMs()).isEqualTo(7);
              assertThat(decided.rawRequestJson()).isEqualTo("{\"req\":1}");
              assertThat(decided.rawResponseJson()).contains("\"cached_tokens\":40");
            });
  }

  @Test
  void httpErrorKeepsItsBodyAsJson() throws Exception {
    BadRequestException error =
        BadRequestException.builder()
            .headers(Headers.builder().build())
            .error(
                ErrorObject.builder()
                    .message("bad schema")
                    .type("invalid_request_error")
                    .code((String) null)
                    .param((String) null)
                    .build())
            .build();

    ModelResponse.Failed failed = OpenAiModelClient.serviceFailure(error, "{}", 7);

    assertThat(failed.code()).isEqualTo(ModelResponse.Failure.HTTP_4XX);
    assertThat(JsonMapper.builder().build().readTree(failed.rawResponseJson()).isObject()).isTrue();
    assertThat(failed.rawResponseJson()).contains("bad schema");
  }

  @Test
  void clientAndCheckExistOnlyWithAKey() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withBean(
                AgentProperties.class,
                () ->
                    new AgentProperties("gpt-5.1", null, null, Duration.ofSeconds(1), 0, 16, false))
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

  private static Response response(String fields) throws Exception {
    String json =
        "{\"id\": \"r1\", \"object\": \"response\", \"created_at\": 0, \"model\": \"gpt-5.1\", "
            + fields
            + ", "
            + USAGE
            + "}";
    return ObjectMappers.jsonMapper().readValue(json, Response.class);
  }
}
