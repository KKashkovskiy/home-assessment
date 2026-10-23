package com.enrola.anna.eval;

import static com.enrola.anna.eval.ScenarioFixtures.turn;
import static com.enrola.anna.eval.Simulator.Moment.AFTER_END;
import static com.enrola.anna.eval.Simulator.Moment.TALKING;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A keyed run of two trials per scenario, two turns and a story, against golden files: a turn that
 * fails a hard check once, a story that passes once and breaks persona once.
 */
class ReportWriterTest {

  static final Scenario.Turn HAPPY =
      turn("turn-happy-pick-a-time", "monday?", Scenario.Expect.NONE, "core", "scheduling");
  static final Scenario.Turn SPANISH =
      turn("turn-non-english", "Hola", Scenario.Expect.NONE, "core", "language");
  static final Scenario.Story STORY =
      ScenarioFixtures.story("story-john-objects-twice", "core", "objection");

  static final List<Trial> TRIALS =
      List.of(
          turnTrial(
              HAPPY,
              1,
              "Monday I've got 9:00 AM. Does that work?",
              Check.expectation("tool_called", true, "called=[get_availability]").at(4),
              Check.hard("length", true, "\"Monday I've got 9:00 AM. Does that work?\"").at(4)),
          turnTrial(
              HAPPY,
              2,
              "Monday I've got 9:00 AM. Does that work?",
              Check.expectation("tool_called", true, "called=[get_availability]").at(4)),
          turnTrial(
              SPANISH,
              1,
              "Hola Lauren",
              Check.expectation("lead_intent", true, "expected=OTHER seen=OTHER").at(2),
              Check.expectation("reply_matches (?i)english", false, "\"Hola Lauren\"").at(2),
              Check.hard("url", false, "only comparato.au links are allowed: \"Hola Lauren\"")
                  .at(2)),
          turnTrial(
              SPANISH,
              2,
              "English only here. Does Monday suit?",
              Check.expectation("reply_matches (?i)english", true, "\"English only\"").at(2)),
          storyTrial(),
          new Trial(
              STORY.id(),
              2,
              TrialStatus.INVALID,
              List.of(
                  Check.hard("length", true, "\"No worries.\"").at(1),
                  Check.expectation("persona", false, "actor claimed to be an AI: \"As an AI\"")),
              List.of(),
              new BigDecimal("0.020"),
              1,
              "c-5e6f",
              new Trial.StoryDetail(
                  List.of(
                      new StoryTurn(
                          0,
                          null,
                          List.of("Hi Lauren, it's Anna."),
                          List.of(),
                          BigDecimal.ZERO,
                          TALKING),
                      new StoryTurn(
                          1,
                          "too busy for this mate",
                          List.of("No worries."),
                          List.of(),
                          new BigDecimal("0.020"),
                          TALKING)),
                  "persona break after 1 lead messages"),
              900));

  static final RunHeader HEADER =
      new RunHeader(
          "2026-10-22T10-41-03Z",
          "v1",
          "gpt-5.1",
          "medium",
          new RunMode.Keyed("eval (EVAL_OPENAI_API_KEY)"),
          "tag core",
          2,
          0.2,
          new BigDecimal("10"),
          Instant.parse("2026-10-22T10:41:03Z"));

  static final List<Scenario> SELECTED = List.of(HAPPY, SPANISH, STORY);

  @TempDir Path dir;

  @Test
  void goldenReport() throws IOException {
    write(List.of());

    assertThat(dir.resolve("report.md"))
        .hasSameTextualContentAs(Path.of("src/test/resources/eval/golden-report.md"));
    assertThat(dir.resolve(ReportWriter.EXIT_CODE)).hasContent("3");
  }

  /** One line per trial; each check carries its gate and the turn it is about. */
  @Test
  void oneJsonLinePerTrial() throws IOException {
    write(List.of());

    List<String> lines = Files.readAllLines(dir.resolve("trials.jsonl"));
    assertThat(lines).hasSize(6);
    assertThat(lines.get(2))
        .startsWith(
            "{\"run\":\"2026-10-22T10-41-03Z\",\"scenario\":\"turn-non-english\",\"trial_no\":1")
        .contains(
            "\"status\":\"FAIL\"",
            "{\"name\":\"reply_matches (?i)english\",\"gate\":\"EXPECTATION\",\"pass\":false,"
                + "\"evidence\":\"\\\"Hola Lauren\\\"\",\"turn\":2}",
            "\"cost_usd\":0.040",
            "\"conversation_id\":\"c-turn-non-english\",\"turn_no\":2,\"stop_reason\":null");
  }

  @Test
  void turnPageShowsTheTurnAndEveryCheck() throws IOException {
    write(List.of());

    assertThat(Files.readString(dir.resolve("turn-non-english.md")))
        .contains(
            "# turn-non-english#1: Title of turn-non-english",
            "Result: **FAIL**",
            "- **Lead (under test):** Hola",
            "- **Anna (reply):** Hola Lauren",
            "| reply_matches (?i)english | expectation | FAIL | \"Hola Lauren\" |",
            "Conversation `c-turn-non-english`, turn 2");
    assertThat(dir.resolve("turn-non-english.2.md")).exists();
  }

  /** The story's first trial turn by turn, against a golden file. */
  @Test
  void storyPageIsTurnByTurn() throws IOException {
    write(List.of());

    assertThat(dir.resolve("story-john-objects-twice.md"))
        .hasSameTextualContentAs(Path.of("src/test/resources/eval/golden-story-page.md"));
    assertThat(Files.readString(dir.resolve("story-john-objects-twice.2.md")))
        .contains(
            "Result: **INVALID**",
            "| persona | expectation | FAIL | actor claimed to be an AI",
            "Stopped: persona break after 1 lead messages");
  }

  @Test
  void notesCloseTheReport() throws IOException {
    write(List.of("first note", "second note"));

    assertThat(Files.readString(dir.resolve("report.md")))
        .endsWith("\nfirst note\n\nsecond note\n");
  }

  /** The run's files under the temporary directory; no row is recorded here. */
  private void write(List<String> notes) {
    var plan = new RunPlan(HEADER, SELECTED, dir, null);
    GateResult gates = Gates.evaluate(TRIALS, SELECTED, HEADER.validityThreshold());
    ReportWriter.write(plan, new RunSummary(TRIALS, 6, gates), notes);
  }

  /** A trial at $0.04 and one model call, PASS when every check passed. */
  private static Trial turnTrial(
      Scenario.Turn scenario, int trialNo, String reply, Check... checks) {
    boolean passed = Arrays.stream(checks).allMatch(Check::pass);
    return new Trial(
        scenario.id(),
        trialNo,
        passed ? TrialStatus.PASS : TrialStatus.FAIL,
        List.of(checks),
        List.of(),
        new BigDecimal("0.040"),
        1,
        "c-" + scenario.id(),
        new Trial.TurnDetail(checks[0].turn(), reply, List.of()),
        1800);
  }

  /** Two turns to the decline, two probes answered by silence, then the end-state checks. */
  private static Trial storyTrial() {
    return new Trial(
        STORY.id(),
        1,
        TrialStatus.PASS,
        List.of(
            Check.hard("length", true, "\"No worries, quick call?\"").at(1),
            Check.expectation("every_reply.one_question_max", true, "questions=1").at(1),
            Check.hard("length", true, "\"Ok, understood. I'll leave it there.\"").at(2),
            Check.hard("reply_after_end", true, "closing replies=1").at(3),
            Check.hard("reply_after_end", true, "closing replies=1").at(4),
            Check.expectation("end_reason", true, "expected=DECLINED seen=DECLINED"),
            Check.expectation("retries_max", true, "max=1 seen=1")),
        List.of(),
        new BigDecimal("0.051"),
        3,
        "c-5e6e",
        new Trial.StoryDetail(
            List.of(
                new StoryTurn(
                    0, null, List.of("Hi Lauren, it's Anna."), List.of(), BigDecimal.ZERO, TALKING),
                new StoryTurn(
                    1,
                    "too busy for this mate",
                    List.of("No worries, quick call?"),
                    List.of(),
                    new BigDecimal("0.021"),
                    TALKING),
                new StoryTurn(
                    2,
                    "nah not interested",
                    List.of("Ok, understood. I'll leave it there."),
                    List.of(),
                    new BigDecimal("0.020"),
                    TALKING),
                new StoryTurn(
                    3, "said no", List.of(), List.of(), new BigDecimal("0.010"), AFTER_END),
                new StoryTurn(4, "ok", List.of(), List.of(), BigDecimal.ZERO, AFTER_END)),
            "goodbye after 2 lead messages; 2 probes sent, 0 replied"),
        5100);
  }
}
