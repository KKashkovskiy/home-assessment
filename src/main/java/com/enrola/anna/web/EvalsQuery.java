package com.enrola.anna.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * What the Evals page's address asks for: the scenario list's filter ({@code q}, {@code kind},
 * {@code tag}), the run whose report to open, the scenario to edit or the kind of a new one, the
 * harness file to open, and the preset the run form starts from. Every part is optional.
 */
public record EvalsQuery(
    @Size(max = 60) String q,
    @Pattern(regexp = "|turn|story") String kind,
    @Pattern(regexp = "[a-z0-9-]{0,40}") String tag,
    @Pattern(regexp = EvalReports.RUN_ID) String run,
    @Pattern(regexp = ScenarioFiles.ID) String edit,
    @Pattern(regexp = "turn|story") String create,
    @Size(max = 80) String file,
    @Size(max = 40) String preset) {

  static final EvalsQuery NONE = new EvalsQuery(null, null, null, null, null, null, null, null);

  /** No preset, or one the run form has. */
  @AssertTrue public boolean isKnownPreset() {
    return preset == null || RunRequest.PRESETS.containsKey(preset);
  }

  /** A row the filter keeps: the search in its id, title or tags, its kind and its tag. */
  boolean keeps(ScenarioFiles.ScenarioRow row) {
    String words = (row.id() + " " + row.title() + " " + row.tags()).toLowerCase(Locale.ROOT);
    return (blank(q) || words.contains(q.strip().toLowerCase(Locale.ROOT)))
        && (blank(kind) || row.kind().equals(kind))
        && (blank(tag) || row.tags().contains(tag));
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
