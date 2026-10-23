package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptVersion;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Which version a request opens, and how a notice lists what the store said. */
class ConfigurationPagesTest {

  static final List<PromptVersion> VERSIONS =
      List.of(version("v1", false), version("v2", true), version("v3", false));

  @ParameterizedTest
  @CsvSource({"v1, v1", "v3, v3", "v9, v2", "'', v2"})
  void aListedVersionOpensElseTheActive(String requested, String opened) {
    assertThat(ConfigurationPages.select(VERSIONS, requested).id()).isEqualTo(opened);
  }

  @Test
  void aRefusalListsEachErrorOnItsOwnLineCutShort() {
    Notice notice =
        ConfigurationPages.notSaved(
            new PromptStore.SaveResult.Rejected(
                List.of("first error", "tool " + "x".repeat(5_000) + " must be strict"),
                List.of("a warning")));

    List<String> lines = notice.text().lines().toList();
    assertThat(lines).hasSize(5).contains("first error", "a warning");
    assertThat(lines.get(2)).hasSize(ConfigurationPages.ITEM_MAX).endsWith("…");
  }

  @ParameterizedTest
  @CsvSource({"159, 159", "160, 160", "161, 160"})
  void anItemIsCutToTheLimitWithAnEllipsis(int length, int shown) {
    assertThat(ConfigurationPages.clip("x".repeat(length))).hasSize(shown);
  }

  @Test
  void aCutNeverSplitsASurrogatePair() {
    String item = "x".repeat(ConfigurationPages.ITEM_MAX - 2) + "😀".repeat(5);

    String cut = ConfigurationPages.clip(item);

    assertThat(cut).isEqualTo("x".repeat(ConfigurationPages.ITEM_MAX - 2) + "…");
  }

  private static PromptVersion version(String id, boolean active) {
    return new PromptVersion(id, Instant.EPOCH, "repo", null, active, "", "", "", "", "");
  }
}
