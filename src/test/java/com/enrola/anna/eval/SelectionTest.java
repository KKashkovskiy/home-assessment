package com.enrola.anna.eval;

import static com.enrola.anna.eval.ScenarioFixtures.story;
import static com.enrola.anna.eval.ScenarioFixtures.turn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The run's filters over four in-memory scenarios. */
class SelectionTest {

  static final Scenario STOP =
      turn("turn-stop-keyword", "STOP", Scenario.Expect.NONE, "core", "opt-out");
  static final Scenario SLOT =
      turn("turn-slot-taken", "12", Scenario.Expect.NONE, "core", "scheduling");
  static final Scenario DST =
      turn("turn-dst-vic", "tuesday", Scenario.Expect.NONE, "scheduling", "p1");
  static final Scenario STORY = story("story-lauren-books", "core");
  static final List<Scenario> ALL = List.of(STOP, SLOT, DST, STORY);

  @Test
  void globAndTagBothApply() {
    assertThat(new Selection(null, "turn-s*", "core", null, null).apply(ALL))
        .containsExactly(STOP, SLOT);
  }

  @Test
  void kindAndPriority() {
    assertThat(new Selection(null, null, null, "story", null).apply(ALL)).containsExactly(STORY);
    assertThat(new Selection(null, null, null, null, "p1").apply(ALL)).containsExactly(DST);
  }

  @Test
  void idsWinOverTheOtherFilters() {
    assertThat(
            new Selection("turn-dst-vic, story-lauren-books", "turn-s*", "core", null, null)
                .apply(ALL))
        .containsExactly(DST, STORY);
  }

  @Test
  void unknownIdsAreNamed() {
    assertThatThrownBy(
            () ->
                new Selection("turn-dst-vic,turn-nope,turn-gone", null, null, null, null)
                    .apply(ALL))
        .isInstanceOf(ScenarioException.class)
        .hasMessageEndingWith("turn-gone, turn-nope");
  }

  @Test
  void kindIsTurnOrStory() {
    assertThatThrownBy(() -> new Selection(null, null, null, "turns", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eval.kind");
  }

  @Test
  void describesWhatItFilters() {
    assertThat(Selection.ALL.describe()).isEqualTo("all");
    assertThat(new Selection(null, "turn-s*", "core", null, null).describe())
        .isEqualTo("filter turn-s*, tag core");
  }
}
