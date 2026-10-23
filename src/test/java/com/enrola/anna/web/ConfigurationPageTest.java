package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.web.ConfigurationPage.VersionRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** When Activate is on, the list's order, and which version the diff starts from. */
class ConfigurationPageTest {

  static final VersionRow V1 = row("v1", true, List.of());
  static final VersionRow V2 = row("v2", false, List.of());
  static final VersionRow V3 = row("v3", false, List.of("tools are not valid JSON"));
  static final List<VersionRow> ROWS = List.of(V1, V2, V3);

  @ParameterizedTest
  @CsvSource({
    "v1, false, false", // already active
    "v2, false, true",
    "v2, true, false", // the form holds a refused save, not v2
    "v3, false, false" // does not load
  })
  void onlyAStoredLoadableInactiveVersionActivates(String id, boolean unsaved, boolean can) {
    assertThat(page(id, unsaved).canActivate()).isEqualTo(can);
  }

  @ParameterizedTest
  @CsvSource({"v1, v2", "v2, v1", "v3, v2"})
  void theDiffStartsFromThePreviousVersionOrTheNextForTheFirst(String selected, String left) {
    assertThat(page(selected, false).diffLeft().id()).isEqualTo(left);
  }

  @Test
  void theListShowsTheNewestFirst() {
    assertThat(page("v1", false).newestFirst()).containsExactly(V3, V2, V1);
  }

  @Test
  void oneVersionHasNothingToDiff() {
    ConfigurationPage alone =
        new ConfigurationPage(
            List.of(V1), V1, VersionForm.of(VersionFormTest.ROW, PromptFile.PROMPT), false, null);

    assertThat(alone.canDiff()).isFalse();
    assertThat(alone.diffLeft()).isEqualTo(V1);
    assertThat(page("v1", false).canDiff()).isTrue();
  }

  private static ConfigurationPage page(String selected, boolean unsaved) {
    return new ConfigurationPage(
        ROWS,
        ROWS.stream().filter(row -> row.id().equals(selected)).findFirst().orElseThrow(),
        VersionForm.of(VersionFormTest.ROW, PromptFile.PROMPT),
        unsaved,
        null);
  }

  private static VersionRow row(String id, boolean active, List<String> loadErrors) {
    return new VersionRow(id, Instant.EPOCH, "repo", null, active, loadErrors);
  }
}
