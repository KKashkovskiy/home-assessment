package com.enrola.anna.eval;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Optional;

/**
 * One run's options, read once from the {@code eval.*} system properties that {@code evals/run.sh}
 * and the Evals page set: {@code runId}, the selection ({@code scenarios}, {@code filter}, {@code
 * tag}, {@code kind}, {@code priority}), {@code budgetUsd}, {@code offline}, {@code dryRun}, and
 * {@code appDb}, the JDBC URL of the database that gets the run's row. A bad value is refused here,
 * before anything runs.
 */
record EvalOptions(
    String runId,
    Selection selection,
    RunMode mode,
    BigDecimal budgetUsd,
    Path dir,
    String appDbUrl) {

  /** The app's database, as {@code application.yml} opens it, so a running app shares it. */
  static final String APP_DB = "jdbc:h2:file:./data/anna;AUTO_SERVER=TRUE";

  /** Where the app keeps its settings overrides. */
  static final Path APP_DATA = Path.of("data");

  static final Path RUNS = Path.of("evals/runs");

  /** {@code null} means the settings' eval cap. */
  EvalOptions {
    if (budgetUsd != null && budgetUsd.signum() <= 0) {
      throw new IllegalArgumentException("eval.budgetUsd must be a positive number of dollars");
    }
  }

  static EvalOptions fromSystemProperties() {
    String runId = System.getProperty("eval.runId", "manual");
    return new EvalOptions(
        runId,
        new Selection(
            property("eval.scenarios"),
            property("eval.filter"),
            property("eval.tag"),
            property("eval.kind"),
            property("eval.priority")),
        modeFromProperties(),
        Optional.ofNullable(property("eval.budgetUsd")).map(EvalOptions::dollars).orElse(null),
        RUNS.resolve(runId),
        System.getProperty("eval.appDb", APP_DB));
  }

  /** A dry run plans whatever else is set; offline needs no key; otherwise the key decides. */
  private static RunMode modeFromProperties() {
    if (Boolean.getBoolean("eval.dryRun")) {
      return new RunMode.DryRun();
    }
    if (Boolean.getBoolean("eval.offline")) {
      return new RunMode.Offline();
    }
    return EvalKey.source().<RunMode>map(RunMode.Keyed::new).orElseGet(RunMode.NoKey::new);
  }

  private static BigDecimal dollars(String value) {
    try {
      return new BigDecimal(value);
    } catch (NumberFormatException _) {
      throw new IllegalArgumentException("eval.budgetUsd must be a positive number of dollars");
    }
  }

  /** The property's value; null when unset or blank. */
  private static String property(String name) {
    String value = System.getProperty(name);
    return value == null || value.isBlank() ? null : value.strip();
  }
}
