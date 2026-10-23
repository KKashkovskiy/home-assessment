package com.enrola.anna.eval;

import static com.enrola.anna.eval.TrialTranscriptTest.LAUREN;
import static com.enrola.anna.eval.TrialTranscriptTest.OFFER;
import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.Decisions;
import com.enrola.anna.agent.InputItem;
import com.enrola.anna.agent.ModelRequest;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.ScriptedModelClient;
import com.enrola.anna.eval.JudgeResult.Verdict;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The judge over a scripted model: a quote found whole in one SMS keeps its verdict, anything else
 * is UNKNOWN with a note, NOT_APPLICABLE needs no quote, and the request carries the rubric, the
 * marked transcript as data and no phone or email.
 */
class JudgeClientTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  static final Rubric RETURN =
      new Rubric(
          "J-SCRIPT-RETURN",
          "Does it return to the script?",
          Rubric.Scope.TURN,
          Rubric.Severity.SOFT,
          Set.of("core"),
          "Answering and stopping is a FAIL.\n",
          new Rubric.Examples(List.of("yes"), List.of("no")));

  final ScriptedModelClient model = new ScriptedModelClient();
  final JudgeClient judge =
      new JudgeClient(
          model,
          ScenarioFixtures.model("gpt-5.1"),
          new JudgeClient.Rules("RULES", "judge_result", null));

  @Test
  void passWithVerbatimEvidence() {
    answers("PASS", "Would one of those work?", "asks");

    assertThat(judge.ask(RETURN, LAUREN, 2))
        .isEqualTo(
            new JudgeResult(
                "J-SCRIPT-RETURN",
                "turn-x",
                1,
                2,
                Verdict.PASS,
                "Would one of those work?",
                "asks",
                ScenarioFixtures.model("gpt-5.1").cost(Decisions.USAGE),
                5));
  }

  /** Surrounding whitespace is not part of a quote; everything else is. */
  @Test
  void quoteIsStrippedNotRetyped() {
    answers("FAIL", "  " + OFFER + "\n", "offers three");

    assertThat(judge.ask(RETURN, LAUREN, 2).evidence()).isEqualTo(OFFER);
  }

  /**
   * A paraphrase, another case, a retyped curly apostrophe, the question's text, a role label, a
   * marker, a span over two messages, a quote under eight characters and no quote at all are not
   * the words of one SMS, so the verdict cannot stand.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "offers three times and asks",
        "would one of those work?",
        "Monday I’ve got 12:00 PM",
        "Does it return to the script?",
        "[UNDER JUDGEMENT]",
        "Anna: Makes sense.",
        "[turn 2] Lead: hospital mainly",
        "covered for?\n[turn 2] Lead: hospital mainly",
        "a",
        "Monday",
        ""
      })
  void fabricatedEvidenceDowngradesToUnknown(String quote) {
    answers("FAIL", quote, "does not return");

    JudgeResult result = judge.ask(RETURN, LAUREN, 2);

    assertThat(result.verdict()).isEqualTo(Verdict.UNKNOWN);
    assertThat(result.evidence()).isNull();
    assertThat(result.note())
        .startsWith("evidence not found verbatim in one message; judge said FAIL with");
    assertThat(result.costUsd()).isPositive();
  }

  /** NOT_APPLICABLE and UNKNOWN stand without a quote; the reason is the note. */
  @ParameterizedTest
  @ValueSource(strings = {"NOT_APPLICABLE", "UNKNOWN"})
  void anAnswerWithoutAVerdictNeedsNoEvidence(String verdict) {
    answers(verdict, "", "the lead asked nothing");

    JudgeResult result = judge.ask(RETURN, LAUREN, 2);

    assertThat(result.verdict()).isEqualTo(Verdict.valueOf(verdict));
    assertThat(result.evidence()).isNull();
    assertThat(result.note()).isEqualTo("the lead asked nothing");
  }

  /** Output the schema should have prevented is read as no answer, never as a PASS. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"verdict\":\"MAYBE\",\"evidence\":\"Would one of those work?\",\"reason\":\"x\"}",
        "{\"evidence\":\"Would one of those work?\"}",
        "\"PASS\"",
        "{\"verdict\":\"pass\",\"evidence\":\"Would one of those work?\",\"reason\":\"x\"}",
        "{\"verdict\":\"SKIPPED_BUDGET\",\"evidence\":\"\",\"reason\":\"x\"}"
      })
  void malformedOutputIsUnknown(String output) {
    model.enqueue(decided(output));

    JudgeResult result = judge.ask(RETURN, LAUREN, 2);

    assertThat(result.verdict()).isEqualTo(Verdict.UNKNOWN);
    assertThat(result.note()).startsWith("judge output malformed: ");
  }

  @Test
  void failedCallIsUnknownWithItsCode() {
    model.enqueue(Decisions.failed(ModelResponse.Failure.TIMEOUT));

    JudgeResult result = judge.ask(RETURN, LAUREN, 2);

    assertThat(result.verdict()).isEqualTo(Verdict.UNKNOWN);
    assertThat(result.note()).isEqualTo("judge call failed: TIMEOUT");
    assertThat(result.costUsd()).isZero();
  }

  @Test
  void noPhoneOrEmailInRequest() {
    answers("PASS", "Would one of those work?", "asks");

    judge.ask(RETURN, LAUREN, 2);

    assertThat(sent())
        .contains("lead Lauren, VIC, provider Bupa, premium unknown")
        .doesNotContain("+61")
        .doesNotContain("@");
  }

  /**
   * Strict output, low effort, no tools, the rules as instructions; the question and its notes,
   * then the scenario line and the marked transcript between the markers, as data.
   */
  @Test
  void requestIsStrictLowEffortWithTheContextAsData() {
    answers("PASS", "Would one of those work?", "asks");

    judge.ask(RETURN, LAUREN, 2);

    ModelRequest request = model.calls().getFirst();
    assertThat(request.schemaName()).isEqualTo("judge_result");
    assertThat(request.reasoningEffort()).isEqualTo("low");
    assertThat(request.tools()).isEmpty();
    assertThat(request.instructions()).isEqualTo("RULES");
    assertThat(sent())
        .isEqualTo(
            """
            Question: Does it return to the script?
            Answering and stopping is a FAIL.

            <<<TRANSCRIPT
            Scenario: turn-x, lead Lauren, VIC, provider Bupa, premium unknown
            Clock: 2026-07-26T10:00+10:00, calendar normal

            %s
            TRANSCRIPT>>>
            """
                .formatted(LAUREN.render(2)));
  }

  /** The repo's own prompt and schema load, so a keyed run cannot fail on them. */
  @Test
  void rulesLoadFromTheRepo() {
    JudgeClient.Rules rules = JudgeClient.Rules.fromFiles();

    assertThat(rules.schemaName()).isEqualTo("judge_result");
    assertThat(rules.schema().path("properties").path("verdict").path("enum").toString())
        .isEqualTo("[\"PASS\",\"FAIL\",\"NOT_APPLICABLE\",\"UNKNOWN\"]");
  }

  private void answers(String verdict, String evidence, String reason) {
    model.enqueue(
        decided(
            JSON.writeValueAsString(
                JSON.createObjectNode()
                    .put("verdict", verdict)
                    .put("evidence", evidence)
                    .put("reason", reason))));
  }

  private static ModelResponse.Decided decided(String output) {
    return new ModelResponse.Decided(JSON.readTree(output), null, Decisions.USAGE, 5, "{}", "{}");
  }

  private String sent() {
    return ((InputItem.UserText) model.calls().getFirst().input().getFirst()).text();
  }
}
