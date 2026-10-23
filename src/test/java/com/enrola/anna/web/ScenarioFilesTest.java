package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.enrola.anna.conversation.NotFoundException;
import com.enrola.anna.web.ScenarioFiles.ScenarioRow;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The harness files over a copy of the repo's layout in a temp directory: two turns, a story and a
 * broken file, one rubric, the prompts and the shipped deny-list. Saves are checked as the harness
 * loads them and confined to their directories.
 */
class ScenarioFilesTest {

  static final String TURN =
      """
      id: %s
      kind: turn
      title: A turn
      tags: [%s]
      lead: lauren
      clock: "2026-07-26T10:00:00+10:00"
      calendar: normal
      message: hi
      expect:
        goal_met: false
      """;

  static final String RUBRIC =
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

  @TempDir Path repo;
  Path evals;
  ScenarioFiles files;

  @BeforeEach
  void copyOfTheRepoLayout() throws IOException {
    evals = repo.resolve("evals");
    Files.createDirectories(evals.resolve("schema"));
    Files.copy(
        Path.of("evals/schema/scenario.schema.json"), evals.resolve("schema/scenario.schema.json"));
    Path guards = Files.createDirectories(repo.resolve("src/main/resources/guards"));
    Files.copy(
        Path.of("src/main/resources/guards/deny-list.yaml"), guards.resolve("deny-list.yaml"));
    write("scenarios/turn-a.yaml", TURN.formatted("turn-a", "core, p0"));
    write("scenarios/turn-b.yaml", TURN.formatted("turn-b", "scheduling"));
    write("scenarios/turn-typo.yaml", TURN.formatted("turn-typo", "core") + "  reply_match: [x]\n");
    write(
        "scenarios/story-s.yaml",
        TURN.formatted("story-s", "core")
            .replace("kind: turn", "kind: story")
            .replace("message: hi", "persona: be nice\nstop_when: [goal_met]"));
    write("rubrics/J-X.yaml", RUBRIC);
    write("prompts/judge.md", "Judge.");
    files = new ScenarioFiles(evals);
  }

  @Test
  void listsEveryFileWithKindTagsAndPriorityAndABrokenOneWithItsProblems() {
    assertThat(files.list())
        .extracting(ScenarioRow::id, ScenarioRow::kind, ScenarioRow::priority, ScenarioRow::loads)
        .containsExactly(
            tuple("story-s", "story", "", true),
            tuple("turn-a", "turn", "p0", true),
            tuple("turn-b", "turn", "", true),
            tuple("turn-typo", "turn", "", false));
    assertThat(files.list().getLast().problems())
        .singleElement()
        .asString()
        .contains("reply_match");
  }

  @Test
  void saveWritesAValidScenario() {
    String yaml = TURN.formatted("turn-new-1", "core");

    assertThat(files.save("turn-new-1", "", yaml)).isEmpty();

    assertThat(files.text("turn-new-1")).isEqualTo(yaml);
  }

  @Test
  void saveRefusesAnIdOtherThanTheFileNameAndWritesNothing() {
    assertThat(files.save("turn-y", "", TURN.formatted("turn-x", "core")))
        .containsExactly("id must equal the file name (turn-y)");
    assertThat(evals.resolve("scenarios/turn-y.yaml")).doesNotExist();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../etc/passwd",
        "turn-A",
        "scenario-x",
        "turn-",
        "story-01234567890123456789012345678901"
      })
  void saveRefusesAnIdOutsideTheShape(String id) {
    assertThat(files.save(id, "", TURN.formatted(id, "core")))
        .containsExactly("id must match (turn|story)-[a-z0-9-]+, up to 36 characters");
  }

  /** Each skeleton loads but for the one field the editor asks the author to fill. */
  @ParameterizedTest
  @CsvSource({"turn, turn-new-2, message:", "story, story-new-1, persona:"})
  void skeletonTakesTheFirstFreeIdAndLacksOnlyItsText(String kind, String id, String field) {
    files.save("turn-new-1", "", TURN.formatted("turn-new-1", "core"));

    String skeleton = files.skeleton(kind);

    assertThat(skeleton).startsWith("id: " + id + "\n");
    assertThat(files.save(id, "", skeleton)).singleElement().asString().startsWith(field);
  }

  /** Opened as itself a scenario saves over its own file; a new one never over another's. */
  @Test
  void aSaveUnderAnIdAnotherScenarioHasIsRefused() throws IOException {
    String yaml = TURN.formatted("turn-a", "core").replace("A turn", "Edited");

    assertThat(files.save("turn-a", "", yaml)).singleElement().asString().startsWith("id ");
    assertThat(files.save("turn-a", "turn-b", yaml)).singleElement().asString().startsWith("id ");
    assertThat(files.save("turn-a", "turn-a", yaml)).isEmpty();
    assertThat(files.text("turn-a")).contains("Edited");
  }

  /** A link planted in a directory reaches nothing outside it, for reading or listing. */
  @Test
  void aLinkToAFileOutsideIsNeitherReadNorListed() throws IOException {
    Path secret = Files.writeString(repo.resolve("secret.txt"), "id: J-X\n");
    Files.createSymbolicLink(evals.resolve("rubrics/J-LINK.yaml"), secret);
    Files.createSymbolicLink(evals.resolve("scenarios/turn-link.yaml"), secret);

    assertThatThrownBy(() -> files.read("rubrics/J-LINK.yaml"))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> files.saveFile("rubrics/J-LINK.yaml", RUBRIC))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> files.text("turn-link")).isInstanceOf(NotFoundException.class);
    assertThat(files.harnessFiles()).doesNotContain("rubrics/J-LINK.yaml");
    assertThat(files.list()).extracting(ScenarioRow::id).doesNotContain("turn-link");
    assertThat(secret).hasContent("id: J-X\n");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "rubrics/../../pom.xml",
        "rubrics/J-NOPE.yaml",
        "prompts/../scenarios/turn-a.yaml",
        "scenarios/turn-a.yaml",
        "../src/main/resources/application.yml"
      })
  void readRefusesANameOffTheList(String name) {
    assertThatThrownBy(() -> files.read(name)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> files.saveFile(name, "x")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void listsTheHarnessFilesThereAreRubricsFirst() {
    assertThat(files.harnessFiles())
        .containsExactly("rubrics/J-X.yaml", "prompts/judge.md", "guards/deny-list.yaml");
  }

  /** Each kind of file is refused as its loader would refuse it, and left as it was. */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "rubrics/J-X.yaml      | question must end with ?",
        "prompts/judge.md      | a prompt must not be empty",
        "guards/deny-list.yaml | does not load",
      })
  void saveFileRefusesWhatItsLoaderWouldAndKeepsTheFile(String name, String problem) {
    String before = files.read(name);
    String broken =
        switch (name) {
          case "rubrics/J-X.yaml" -> RUBRIC.replace("Does it?", "It does.");
          case "prompts/judge.md" -> "  \n";
          default -> "rules: [";
        };

    assertThat(files.saveFile(name, broken)).singleElement().asString().contains(problem);
    assertThat(files.read(name)).isEqualTo(before);
  }

  @Test
  void saveFileWritesAFileThatPasses() {
    String edited = RUBRIC.replace("Does it?", "Does it now?");

    assertThat(files.saveFile("rubrics/J-X.yaml", edited)).isEmpty();

    assertThat(files.read("rubrics/J-X.yaml")).isEqualTo(edited);
  }

  private void write(String name, String text) throws IOException {
    Path file = evals.resolve(name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }
}
