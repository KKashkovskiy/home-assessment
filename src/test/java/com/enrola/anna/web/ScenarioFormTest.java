package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.eval.ScenarioLoader;
import com.enrola.anna.web.ScenarioForm.HistoryRow;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The editor's form over the repo's scenarios: filled from a file and posted untouched it leaves
 * the file as written; a changed field changes its key alone, and what the form has no field for
 * stays.
 */
class ScenarioFormTest {

  static final Path SCENARIOS = Path.of("evals/scenarios");

  static List<Path> scenarioFiles() throws IOException {
    try (Stream<Path> files = Files.list(SCENARIOS)) {
      return files.filter(file -> file.toString().endsWith(".yaml")).sorted().toList();
    }
  }

  @ParameterizedTest
  @MethodSource("scenarioFiles")
  void anUntouchedFormLeavesTheScenarioAsItWas(Path file) throws IOException {
    String yaml = Files.readString(file);

    String saved = filledFrom(yaml).text().orElseThrow();

    assertThat(ScenarioForm.read(saved)).isEqualTo(ScenarioForm.read(yaml));
  }

  /** An unticked box cannot tell false from no key, so a false the file holds stays. */
  @Test
  void anUntickedBoxKeepsAFalseTheFileHolds() {
    String yaml =
        ScenarioFilesTest.TURN
            .formatted("turn-x", "core")
            .replace("kind: turn", "kind: story")
            .replace("message: hi", "persona: be nice\nstop_when: [goal_met]")
            .replace("expect:\n", "expect:\n  every_reply:\n    one_question_max: false\n");
    MultiValueMap<String, String> form = fields(yaml);
    form.set("title", "Edited");

    JsonNode tree = ScenarioForm.read(new ScenarioForm(form).text().orElseThrow()).orElseThrow();

    assertThat(tree.path("expect").path("every_reply").path("one_question_max").isBoolean())
        .isTrue();
  }

  @Test
  void aChangedFieldChangesItsKeyAndKeepsWhatTheFormDoesNotShow() throws IOException {
    String yaml = Files.readString(SCENARIOS.resolve("story-lauren-books.yaml"));
    MultiValueMap<String, String> form = fields(yaml);
    form.set("title", "Lauren books on Monday");
    form.set("expect.unsubscribed", "");
    form.set("expect.every_reply.one_question_max", "");

    ObjectNode saved = ScenarioForm.read(new ScenarioForm(form).text().orElseThrow()).orElseThrow();

    ObjectNode original = ScenarioForm.read(yaml).orElseThrow();
    original.put("title", "Lauren books on Monday");
    ((ObjectNode) original.get("expect")).remove("unsubscribed");
    ((ObjectNode) original.path("expect").path("every_reply")).remove("one_question_max");
    assertThat(saved).isEqualTo(original);
    assertThat(saved.path("config").path("offline_lines")).hasSize(6);
  }

  @Test
  void fieldsMapToTheTypesTheSchemaReadsAndEmptyOnesGo() {
    MultiValueMap<String, String> form = fields(ScenarioFilesTest.TURN.formatted("turn-x", "core"));
    form.set("seed.objections_used", "1");
    form.set("expect.tool_called", "[]");
    form.set("expect.goal_met", "true");
    form.set("expect.reply_matches", "(?i)hello\r\n\r\n^ok$");
    form.set("tags", "core, p0,");
    form.add("history.speaker", "agent");
    form.add("history.text", "Hi?");
    form.add("history.offered_slots", "Mon 27 Jul 12:00 PM AEST, Mon 27 Jul 1:00 PM AEST");

    JsonNode tree = ScenarioForm.read(new ScenarioForm(form).text().orElseThrow()).orElseThrow();

    assertThat(tree.path("seed").path("objections_used").isInt()).isTrue();
    assertThat(tree.path("expect").path("tool_called").isArray()).isTrue();
    assertThat(tree.path("expect").path("tool_called")).isEmpty();
    assertThat(tree.path("expect").path("goal_met").booleanValue()).isTrue();
    assertThat(tree.path("expect").path("reply_matches")).hasSize(2);
    assertThat(ScenarioForm.fieldTexts(tree).get("tags")).isEqualTo("core, p0");
    assertThat(ScenarioForm.history(tree))
        .containsExactly(
            new HistoryRow("agent", "Hi?", "Mon 27 Jul 12:00 PM AEST, Mon 27 Jul 1:00 PM AEST"));
  }

  /** The other kind's fields go with a switch, so the schema sees one kind or the other. */
  @Test
  void aTurnSwitchedToAStoryLosesItsHistoryMessageAndSeed() throws IOException {
    MultiValueMap<String, String> form =
        fields(Files.readString(SCENARIOS.resolve("turn-reschedule-after-booking.yaml")));
    form.set("kind", "story");
    form.set("persona", "You are Lauren.");
    form.set("stop_when", "goal_met");

    JsonNode tree = ScenarioForm.read(new ScenarioForm(form).text().orElseThrow()).orElseThrow();

    assertThat(tree.has("history") || tree.has("message") || tree.has("seed")).isFalse();
    assertThat(tree.path("persona").stringValue()).isEqualTo("You are Lauren.");
    assertThat(
            new ScenarioLoader(Path.of("evals/schema/scenario.schema.json"))
                .validate(
                    new ScenarioForm(form).text().orElseThrow(), "turn-reschedule-after-booking"))
        .isInstanceOf(ScenarioLoader.Valid.class);
  }

  @Test
  void theYamlViewsTextGoesAsTyped() {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("source", "yaml");
    form.add("yaml", "id: turn-x\n# a comment the form would drop\n");

    assertThat(new ScenarioForm(form).text())
        .contains("id: turn-x\n# a comment the form would drop\n");
  }

  /** The form as the editor renders it from {@code yaml}, posted back unchanged. */
  static ScenarioForm filledFrom(String yaml) {
    return new ScenarioForm(fields(yaml));
  }

  static MultiValueMap<String, String> fields(String yaml) {
    JsonNode tree = ScenarioForm.read(yaml).orElseThrow();
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    ScenarioForm.fieldTexts(tree).forEach(form::add);
    form.add("source", "form");
    form.add("yaml", yaml);
    List<HistoryRow> history = ScenarioForm.history(tree);
    history.forEach(
        row -> {
          form.add("history.speaker", row.speaker());
          form.add("history.text", row.text());
          form.add("history.offered_slots", row.offeredSlots());
        });
    return form;
  }
}
