package com.enrola.anna.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every flag {@code evals/run.sh} takes, as the run form posts them. {@link #toArgs} turns them
 * into the {@code -Deval.*} properties the script passes for the same flags, so a run from the page
 * is the run the script would make. Each value is held to a narrow shape here, since it reaches a
 * command line, though as one argument and never through a shell. A null budget is the settings'
 * eval cap.
 */
public record RunRequest(
    List<
            @Pattern(
                regexp = ScenarioFiles.ID,
                message = "a scenario id is turn- or story- and lowercase")
            String>
        scenarios,
    @Pattern(regexp = "[a-z0-9*?{},\\[\\]-]{0,60}", message = "filter is a glob on scenario ids") String filter,
    @Pattern(regexp = "[a-z0-9-]{0,40}", message = "tag is lowercase letters, digits and hyphens") String tag,
    @Pattern(regexp = "|turn|story", message = "kind is turn, story or any") String kind,
    @Pattern(regexp = "|p0|p1", message = "priority is p0, p1 or any") String priority,
    @Min(value = 1, message = "trials is a whole number from 1 to 20") @Max(value = 20, message = "trials is a whole number from 1 to 20") int trials,
    @DecimalMin(value = "0", message = "the validity threshold is a share from 0 to 1") @DecimalMax(value = "1", message = "the validity threshold is a share from 0 to 1") double validityThreshold,
    @Pattern(regexp = "advisory|none", message = "judges is advisory or none") String judges,
    Boolean offline,
    @DecimalMin(value = "0.01", message = "the budget is from $0.01 to $100") @DecimalMax(value = "100", message = "the budget is from $0.01 to $100") BigDecimal budgetUsd,
    Boolean dryRun)
    implements Serializable {

  /** What the harness takes when {@code --validity-threshold} is not given. */
  static final double VALIDITY_THRESHOLD = 0.2;

  private static final BigDecimal THREE = new BigDecimal("3");
  private static final BigDecimal EIGHT = new BigDecimal("8");
  private static final BigDecimal TEN = new BigDecimal("10");

  /** The run form's presets, in the order its select lists them. */
  static final Map<String, RunRequest> PRESETS = presets();

  /**
   * A form field left out is empty, as a cleared field is, and a button not pressed false. The ids
   * typed into the field, comma-separated, and those of the rows ticked make one list.
   */
  public RunRequest {
    scenarios =
        scenarios == null
            ? List.of()
            : scenarios.stream()
                .flatMap(ids -> Arrays.stream(ids.split(",", -1)))
                .map(String::strip)
                .filter(id -> !id.isEmpty())
                .distinct()
                .toList();
    filter = filter == null ? "" : filter;
    tag = tag == null ? "" : tag;
    kind = kind == null ? "" : kind;
    priority = priority == null ? "" : priority;
    judges = judges == null ? "advisory" : judges;
    offline = Boolean.TRUE.equals(offline);
    dryRun = Boolean.TRUE.equals(dryRun);
  }

  /** This request for {@code scenario} alone, as its row's Run button asks; no filter applies. */
  RunRequest only(String scenario) {
    return new RunRequest(
        List.of(scenario),
        "",
        "",
        "",
        "",
        trials,
        validityThreshold,
        judges,
        offline,
        budgetUsd,
        dryRun);
  }

  /**
   * The {@code -Deval.*} properties {@code run.sh} passes for the flags this request sets, in the
   * order its usage lists them; a value at the harness's default is left out, as an unset flag is.
   */
  List<String> toArgs() {
    List<String> args = new ArrayList<>();
    if (!scenarios.isEmpty()) {
      args.add("-Deval.scenarios=" + String.join(",", scenarios));
    }
    addUnlessBlank(args, "filter", filter);
    addUnlessBlank(args, "tag", tag);
    addUnlessBlank(args, "kind", kind);
    addUnlessBlank(args, "priority", priority);
    if (trials != 1) {
      args.add("-Deval.trials=" + trials);
    }
    if (validityThreshold != VALIDITY_THRESHOLD) {
      args.add("-Deval.validityThreshold=" + validityThreshold);
    }
    if (judges.equals("none")) {
      args.add("-Deval.judges=none");
    }
    if (offline) {
      args.add("-Deval.offline=true");
    }
    if (budgetUsd != null) {
      args.add("-Deval.budgetUsd=" + budgetUsd.toPlainString());
    }
    if (dryRun) {
      args.add("-Deval.dryRun=true");
    }
    return List.copyOf(args);
  }

  private static void addUnlessBlank(List<String> args, String property, String value) {
    if (!value.isBlank()) {
      args.add("-Deval." + property + "=" + value);
    }
  }

  private static Map<String, RunRequest> presets() {
    Map<String, RunRequest> presets = new LinkedHashMap<>();
    presets.put(
        "core-offline",
        new RunRequest(
            List.of(), "", "core", "", "", 1, VALIDITY_THRESHOLD, "none", true, null, false));
    presets.put(
        "core-key",
        new RunRequest(
            List.of(), "", "core", "", "", 1, VALIDITY_THRESHOLD, "none", false, THREE, false));
    presets.put(
        "full-judges",
        new RunRequest(
            List.of(), "", "", "", "", 1, VALIDITY_THRESHOLD, "advisory", false, TEN, false));
    presets.put(
        "stories-k3",
        new RunRequest(
            List.of(), "", "", "story", "", 3, VALIDITY_THRESHOLD, "none", false, EIGHT, false));
    return Collections.unmodifiableMap(presets);
  }
}
