package com.enrola.anna.eval;

import static com.enrola.anna.eval.ScenarioFixtures.turn;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A keyed run's files for one passed and one failed trial: the report against a golden file. */
class ReportWriterTest {

  static final Scenario.Turn HAPPY =
      turn("turn-happy-pick-a-time", "monday?", Scenario.Expect.NONE, "core", "scheduling");
  static final Scenario.Turn SPANISH =
      turn("turn-non-english", "Hola", Scenario.Expect.NONE, "core", "language");

  static final List<Trial> TRIALS =
      List.of(
          new Trial(
              HAPPY.id(),
              "turn",
              TrialStatus.PASS,
              List.of(
                  Check.expectation("tool_called", true, "called=[get_availability]").at(4),
                  Check.hard("length", true, "\"Monday I've got 9:00 AM. Does that work?\"").at(4)),
              List.of(),
              new BigDecimal("0.041"),
              2,
              "c-1a2b",
              4,
              "Monday I've got 9:00 AM. Does that work?",
              List.of("get_availability {\"args\":\"{}\"}"),
              2410),
          new Trial(
              SPANISH.id(),
              "turn",
              TrialStatus.FAIL,
              List.of(
                  Check.expectation("lead_intent", true, "expected=OTHER seen=OTHER").at(2),
                  Check.expectation("reply_matches (?i)english", false, "\"Hola Lauren\"").at(2),
                  Check.hard("url", false, "only comparato.au links are allowed: \"Hola Lauren\"")
                      .at(2)),
              List.of(),
              new BigDecimal("0.036"),
              1,
              "c-3c4d",
              2,
              "Hola Lauren",
              List.of(),
              1800));

  static final RunHeader HEADER =
      new RunHeader(
          "2026-10-22T10-41-03Z",
          "v1",
          "gpt-5.1",
          "medium",
          new RunMode.Keyed("eval (EVAL_OPENAI_API_KEY)"),
          "tag core",
          new BigDecimal("10"),
          Instant.parse("2026-10-22T10:41:03Z"));

  @TempDir Path dir;

  @Test
  void goldenReport() throws IOException {
    ReportWriter.write(plan(), new RunSummary(TRIALS, 2), List.of());

    assertThat(dir.resolve("report.md"))
        .hasSameTextualContentAs(Path.of("src/test/resources/eval/golden-report.md"));
  }

  /** One line per trial; each check carries its gate and the turn it is about. */
  @Test
  void oneJsonLinePerTrial() throws IOException {
    ReportWriter.write(plan(), new RunSummary(TRIALS, 2), List.of());

    List<String> lines = Files.readAllLines(dir.resolve("trials.jsonl"));
    assertThat(lines).hasSize(2);
    assertThat(lines.get(1))
        .startsWith("{\"run\":\"2026-10-22T10-41-03Z\",\"scenario\":\"turn-non-english\"")
        .contains(
            "\"status\":\"FAIL\"",
            "{\"name\":\"reply_matches (?i)english\",\"gate\":\"EXPECTATION\",\"pass\":false,"
                + "\"evidence\":\"\\\"Hola Lauren\\\"\",\"turn\":2}",
            "\"cost_usd\":0.036",
            "\"conversation_id\":\"c-3c4d\",\"turn_no\":2,\"duration_ms\":1800}");
  }

  @Test
  void scenarioPageShowsTheTurnAndEveryCheck() throws IOException {
    ReportWriter.write(plan(), new RunSummary(TRIALS, 2), List.of());

    assertThat(Files.readString(dir.resolve("turn-non-english.md")))
        .contains(
            "# turn-non-english: Title of turn-non-english",
            "Result: **FAIL**",
            "- **Lead (under test):** Hola",
            "- **Anna (reply):** Hola Lauren",
            "| reply_matches (?i)english | expectation | FAIL | \"Hola Lauren\" |",
            "Conversation `c-3c4d`, turn 2");
  }

  /** A story's page shows its persona, not an empty transcript. */
  @Test
  void storyPageShowsThePersona() throws IOException {
    Scenario.Story story = ScenarioFixtures.story("story-lauren-books", "core");
    var plan = new RunPlan(HEADER, List.of(story), dir, null);

    ReportWriter.write(
        plan,
        new RunSummary(List.of(Trial.skipped(story, TrialStatus.SKIPPED_KIND)), 1),
        List.of());

    assertThat(Files.readString(dir.resolve("story-lauren-books.md")))
        .contains("## Persona", "Books Monday morning.", "Stops when: booked; 12 messages");
  }

  @Test
  void notesCloseTheReport() throws IOException {
    ReportWriter.write(plan(), new RunSummary(TRIALS, 2), List.of("first note", "second note"));

    assertThat(Files.readString(dir.resolve("report.md")))
        .endsWith("\nfirst note\n\nsecond note\n");
  }

  /** The two scenarios written under the temporary directory; no row is recorded here. */
  private RunPlan plan() {
    return new RunPlan(HEADER, List.of(HAPPY, SPANISH), dir, null);
  }
}
