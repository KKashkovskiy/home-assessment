package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.config.Settings.CalendarPreset;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The scenario files on disk, and the schema's teeth on files written for the test. */
class ScenarioLoaderTest {

  static final String HEADER =
      """
      kind: turn
      title: t
      lead: lauren
      clock: "2026-07-26T10:00:00+10:00"
      calendar: normal
      """;

  final ScenarioLoader loader = new ScenarioLoader();

  @TempDir Path dir;

  @Test
  void everyFileValidatesAndIdMatchesName() throws IOException {
    List<Scenario> all = loader.loadAll(ScenarioLoader.SCENARIOS);

    try (Stream<Path> files = Files.list(ScenarioLoader.SCENARIOS)) {
      assertThat(all)
          .extracting(Scenario::id)
          .containsExactlyElementsOf(
              files
                  .map(file -> file.getFileName().toString().replace(".yaml", ""))
                  .sorted()
                  .toList());
    }
    assertThat(all)
        .filteredOn(scenario -> scenario.kind().equals("turn"))
        .hasSizeGreaterThanOrEqualTo(41);
  }

  @Test
  void mapsSnakeCaseFieldsAndPresets() {
    Scenario.Turn taken =
        (Scenario.Turn)
            loader.load(ScenarioLoader.SCENARIOS.resolve("turn-slot-taken-on-booking.yaml"));
    Scenario.Turn booked =
        (Scenario.Turn)
            loader.load(ScenarioLoader.SCENARIOS.resolve("turn-reschedule-after-booking.yaml"));

    assertThat(taken.calendar()).isEqualTo(CalendarPreset.SLOT_TAKEN);
    assertThat(taken.clock()).hasToString("2026-07-26T10:00+10:00");
    assertThat(taken.lead().name()).isEqualTo("lauren");
    assertThat(taken.history().getLast().offeredSlots()).hasSize(3);
    assertThat(taken.expect().toolCalled()).containsExactly("book_call", "get_availability");
    assertThat(booked.seed().ended()).isEqualTo(EndReason.BOOKED);
    assertThat(booked.seed().booking()).isEqualTo("Mon 27 Jul 12:00 PM AEST");
  }

  @Test
  void inlineLeadBecomesALeadInE164() throws IOException {
    Path file =
        write(
            "turn-inline",
            HEADER.replace(
                    "lead: lauren", "lead: {given_name: Priya, phone: \"0491570157\", state: null}")
                + "message: hi\nexpect:\n  flags: [TZ_ASSUMED]\n");

    Scenario.Turn inline = (Scenario.Turn) loader.load(file);

    assertThat(inline.lead().name()).isNull();
    assertThat(inline.lead().inline().phone()).isEqualTo("+61491570157");
    assertThat(inline.lead().inline().state()).isNull();
    assertThat(inline.seed()).isEqualTo(Scenario.Seed.NONE);
  }

  @Test
  void rejectsUnknownField() throws IOException {
    Path file = write("turn-typo", HEADER + "message: hi\nexpect:\n  reply_match: [\"x\"]\n");

    assertThatThrownBy(() -> loader.load(file))
        .isInstanceOf(ScenarioException.class)
        .hasMessageContaining("reply_match");
  }

  /** A regex that does not compile fails at load, so no trial can crash on it. */
  @Test
  void rejectsABadRegexNamingTheField() throws IOException {
    Path file =
        write(
            "turn-bad-regex",
            HEADER + "message: hi\nexpect:\n  reply_not_matches: [\"(unclosed\"]\n");

    assertThatThrownBy(() -> loader.load(file))
        .isInstanceOf(ScenarioException.class)
        .hasMessageStartingWith("turn-bad-regex.yaml: expect.reply_not_matches has a bad regex");
  }

  @Test
  void rejectsTurnWithPersona() throws IOException {
    Path file =
        write(
            "turn-persona", HEADER + "message: hi\npersona: be nice\nexpect:\n  goal_met: false\n");

    assertThatThrownBy(() -> loader.load(file))
        .isInstanceOf(ScenarioException.class)
        .hasMessageContaining("persona");
  }

  @Test
  void rejectsHistoryStartingWithTheLead() throws IOException {
    Path file =
        write(
            "turn-lead-first",
            HEADER + "history:\n  - lead: hi\nmessage: hi\nexpect:\n  goal_met: false\n");

    assertThatThrownBy(() -> loader.load(file))
        .isInstanceOf(ScenarioException.class)
        .hasMessageContaining("agent");
  }

  @Test
  void rejectsIdOtherThanTheFileName() throws IOException {
    Path file = dir.resolve("turn-other.yaml");
    Files.writeString(
        file, "id: turn-else\n" + HEADER + "message: hi\nexpect:\n  goal_met: false\n");

    assertThatThrownBy(() -> loader.load(file))
        .isInstanceOf(ScenarioException.class)
        .hasMessageContaining("file name");
  }

  /** A stop condition outside the grammar fails at load, named with the file. */
  @Test
  void rejectsAStoryStopConditionOutsideTheGrammar() throws IOException {
    Path file = write("story-typo", story("stop_when: [goal met]\n"));

    assertThatThrownBy(() -> loader.load(file))
        .isInstanceOf(ScenarioException.class)
        .hasMessage("story-typo.yaml: unknown stop_when: goal met");
  }

  /** A turn limit is a turn scenario's override; a story would ignore it. */
  @Test
  void rejectsMaxTurnsOnAStory() throws IOException {
    Path file = write("story-max-turns", story("stop_when: [goal_met]\nconfig:\n  max_turns: 3\n"));

    assertThatThrownBy(() -> loader.load(file))
        .isInstanceOf(ScenarioException.class)
        .hasMessageStartingWith("story-max-turns.yaml: ");
  }

  /** A story's header and persona, then {@code rest}. */
  private static String story(String rest) {
    return HEADER.replace("kind: turn", "kind: story")
        + "persona: be nice\nexpect:\n  goal_met: true\n"
        + rest;
  }

  /** A scenario file named after {@code id}, with that id. */
  private Path write(String id, String body) throws IOException {
    Path file = dir.resolve(id + ".yaml");
    Files.writeString(file, "id: " + id + "\n" + body);
    return file;
  }
}
