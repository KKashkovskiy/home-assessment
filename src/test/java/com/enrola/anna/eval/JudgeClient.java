package com.enrola.anna.eval;

import com.enrola.anna.agent.InputItem;
import com.enrola.anna.agent.ModelClient;
import com.enrola.anna.agent.ModelRequest;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.eval.JudgeResult.Verdict;
import com.enrola.anna.lead.Lead;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks one rubric question about one trial's transcript, on a client and model of its own, never
 * Anna's model bean: strict {@code judge_result} output at low reasoning effort, with the scenario
 * line and the transcript between markers as data. The answer is PASS, FAIL, NOT_APPLICABLE or
 * UNKNOWN. A PASS or FAIL needs a quote of at least {@value #MIN_QUOTE_CHARS} characters found
 * whole in one SMS the lead or Anna's model wrote, or it becomes UNKNOWN with a note; so does
 * malformed output or a failed call, never PASS.
 */
final class JudgeClient {

  /**
   * The judge's system prompt, {@code evals/prompts/judge.md}, and its strict output schema with
   * its name, from {@code evals/schema/judge-result.schema.json}.
   */
  record Rules(String prompt, String schemaName, JsonNode schema) {

    static Rules fromFiles() {
      Path schema = Path.of("evals/schema/judge-result.schema.json");
      try {
        JsonNode result = JSON.readTree(Files.readString(schema));
        return new Rules(
            EvalModels.prompt("judge.md"), result.path("name").asString(), result.path("schema"));
      } catch (IOException e) {
        throw new UncheckedIOException("cannot read " + schema, e);
      }
    }
  }

  /** The question is specific, so cost per run matters more than the last few percent. */
  static final String EFFORT = "low";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * A shorter quote, a word or a letter, is found in almost any transcript and so proves nothing.
   */
  static final int MIN_QUOTE_CHARS = 8;

  /** Room for the reasoning tokens, which count toward the cap, as well as the answer. */
  private static final int MAX_OUTPUT_TOKENS = 1000;

  /** What a judge answered, once read and checked. */
  private record Answer(Verdict verdict, String evidence, String note) {}

  private final ModelClient judge;
  private final EvalModels.Model model;
  private final Rules rules;

  JudgeClient(ModelClient judge, EvalModels.Model model, Rules rules) {
    this.judge = judge;
    this.model = model;
    this.rules = rules;
  }

  /** {@code rubric} about the turn {@code turnNo} of {@code transcript}, or all of it when null. */
  JudgeResult ask(Rubric rubric, TrialTranscript transcript, Integer turnNo) {
    ModelResponse response = judge.call(request(rubric, transcript, turnNo));
    Answer answer =
        switch (response) {
          case ModelResponse.Decided decided -> answer(decided.output(), transcript);
          case ModelResponse.Failed failed ->
              new Answer(Verdict.UNKNOWN, null, "judge call failed: " + failed.code());
          case ModelResponse.ToolCalls _ ->
              new Answer(Verdict.UNKNOWN, null, "judge call failed: it called a tool");
        };
    return new JudgeResult(
        rubric.id(),
        transcript.scenario().id(),
        transcript.trialNo(),
        turnNo,
        answer.verdict(),
        answer.evidence(),
        answer.note(),
        model.cost(response.usage()),
        response.latencyMs());
  }

  private ModelRequest request(Rubric rubric, TrialTranscript transcript, Integer turnNo) {
    return new ModelRequest(
        model.name(),
        rules.prompt(),
        List.of(new InputItem.UserText(question(rubric, transcript, turnNo))),
        rules.schemaName(),
        rules.schema(),
        List.of(),
        EFFORT,
        null,
        MAX_OUTPUT_TOKENS);
  }

  /**
   * The question and its notes, then between the markers the scenario's context, without phone or
   * email, and the transcript.
   */
  private static String question(Rubric rubric, TrialTranscript transcript, Integer turnNo) {
    Scenario scenario = transcript.scenario();
    Lead lead = transcript.lead();
    return """
        Question: %s
        %s
        <<<TRANSCRIPT
        Scenario: %s, lead %s, %s, provider %s, premium %s
        Clock: %s, calendar %s

        %s
        TRANSCRIPT>>>
        """
        .formatted(
            rubric.question().strip(),
            rubric.notes() == null ? "" : rubric.notes().strip() + "\n",
            scenario.id(),
            lead.givenName(),
            Objects.requireNonNullElse(lead.state(), "state unknown"),
            Objects.requireNonNullElse(lead.currentProvider(), "none"),
            Objects.requireNonNullElse(lead.currentPremium(), "unknown"),
            scenario.clock(),
            scenario.calendar().value(),
            transcript.render(turnNo));
  }

  /** The output as an answer: NOT_APPLICABLE or UNKNOWN as given, a PASS or FAIL once verified. */
  private static Answer answer(JsonNode output, TrialTranscript transcript) {
    String evidence = output.path("evidence").asString("").strip();
    String reason = output.path("reason").asString("").strip();
    return switch (output.path("verdict").asString("")) {
      case "NOT_APPLICABLE" -> new Answer(Verdict.NOT_APPLICABLE, null, reason);
      case "UNKNOWN" -> new Answer(Verdict.UNKNOWN, null, reason);
      case "PASS" -> verified(Verdict.PASS, evidence, reason, transcript);
      case "FAIL" -> verified(Verdict.FAIL, evidence, reason, transcript);
      default ->
          new Answer(
              Verdict.UNKNOWN, null, "judge output malformed: " + Checks.quote(output.toString()));
    };
  }

  /**
   * Exact and case-sensitive within one SMS: a paraphrase, a retyped curly quote, a role label or
   * marker, a span over two messages, a quote from fixed text or scenario history, or one too short
   * to prove anything makes the verdict UNKNOWN, with the claimed quote in the note for a human to
   * decide.
   */
  private static Answer verified(
      Verdict verdict, String evidence, String reason, TrialTranscript transcript) {
    if (evidence.length() < MIN_QUOTE_CHARS || !transcript.says(evidence)) {
      return new Answer(
          Verdict.UNKNOWN,
          null,
          "evidence not found verbatim in one model or lead message; judge said %s with %s"
              .formatted(verdict, Checks.quote(evidence)));
    }
    return new Answer(verdict, evidence, reason);
  }
}
