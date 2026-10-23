package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The repo's sixteen rubrics load, and each structural rule refuses a broken file. */
class RubricLoaderTest {

  /** A valid {@code J-X.yaml}; each case below swaps one line. */
  static final String VALID =
      """
      id: J-X
      question: "Does it?"
      scope: turn
      severity: soft
      applies_to: [core]
      examples:
        pass: ["yes"]
        fail: ["no"]
      """;

  @TempDir Path dir;

  @Test
  void loadsAllSixteen() throws IOException {
    List<Rubric> rubrics = RubricLoader.load(RubricLoader.RUBRICS);

    assertThat(rubrics)
        .extracting(Rubric::id)
        .containsExactly(
            "J-ABUSE-HANDLING",
            "J-AI-DISCLOSURE",
            "J-BOOKING-COMMS",
            "J-CALL-ASK",
            "J-EMPATHY-VULNERABLE",
            "J-GRACEFUL-CLOSE",
            "J-GROUNDED-FACTS",
            "J-HUMAN-REQUEST",
            "J-NO-HEALTH-PROBING",
            "J-NO-PRODUCT-ADVICE",
            "J-OBJECTION-ONE-RETRY",
            "J-PERSONALISATION",
            "J-REQUIRED-INFO",
            "J-SCRIPT-RETURN",
            "J-SMS-STYLE",
            "J-STAY-IN-ROLE");
  }

  @Test
  void readsEveryField() throws IOException {
    Files.writeString(dir.resolve("J-X.yaml"), VALID + "notes: Sharper.\n");

    assertThat(RubricLoader.load(dir))
        .containsExactly(
            new Rubric(
                "J-X",
                "Does it?",
                Rubric.Scope.TURN,
                Rubric.Severity.SOFT,
                Set.of("core"),
                "Sharper.",
                new Rubric.Examples(List.of("yes"), List.of("no"))));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "id: J-X                  | id: J-Y                | id J-Y != file name",
        "question: \"Does it?\"   | question: It does.     | question must end with ?",
        "scope: turn              | ''                     | needs a scope, turn or transcript",
        "scope: turn              | scope: whole           | whole",
        "severity: soft           | ''                     | needs a severity, hard or soft",
        "applies_to: [core]       | applies_to: []         | needs at least one applies_to tag",
        "fail: [\"no\"]           | fail: []               | at least one pass and one fail example",
        "severity: soft           | severity: soft\\nnote: x | note",
      })
  void refusesABrokenRubric(String line, String broken, String message) throws IOException {
    Files.writeString(dir.resolve("J-X.yaml"), VALID.replace(line, broken.replace("\\n", "\n")));

    assertThatThrownBy(() -> RubricLoader.load(dir)).hasMessageContaining(message);
  }

  /** A rubric under another name would be loaded under an id nobody looks for. */
  @Test
  void refusesAFileNamedOutsideThePattern() throws IOException {
    Files.writeString(dir.resolve("sms-style.yaml"), VALID);

    assertThatThrownBy(() -> RubricLoader.load(dir))
        .hasMessageContaining("a rubric file is named J-<ID>.yaml");
  }

  /** A tag no scenario carries would make its rubric silently never apply. */
  @Test
  void everyTagIsOnAScenario() throws IOException {
    Set<String> tags =
        new ScenarioLoader()
            .loadAll(ScenarioLoader.SCENARIOS).stream()
                .flatMap(scenario -> scenario.tags().stream())
                .collect(Collectors.toSet());

    assertThat(RubricLoader.load(RubricLoader.RUBRICS))
        .allSatisfy(rubric -> assertThat(tags).as(rubric.id()).containsAll(rubric.appliesTo()));
  }
}
