package com.enrola.anna.eval;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * One run's options, read once from the {@code eval.*} system properties that {@code evals/run.sh}
 * and the Evals page set: {@code runId}, the selection ({@code scenarios}, {@code filter}, {@code
 * tag}, {@code kind}, {@code priority}), {@code budgetUsd}, {@code trials} per scenario, the
 * validity gate's {@code validityThreshold}, {@code offline}, {@code dryRun}, {@code judges}
 * ({@code advisory}, the default, or {@code none}; only a keyed run asks them), and {@code appDb},
 * the JDBC URL of the database that gets the run's row. A bad value is refused here, before
 * anything runs.
 */
record EvalOptions(
    String runId,
    Selection selection,
    RunMode mode,
    BigDecimal budgetUsd,
    int trials,
    double validityThreshold,
    boolean judges,
    Path dir,
    String appDbUrl) {

  /** The app's database, as {@code application.yml} opens it, so a running app shares it. */
  static final String APP_DB = "jdbc:h2:file:./data/anna;AUTO_SERVER=TRUE";

  /** Where the app keeps its settings overrides. */
  static final Path APP_DATA = Path.of("data");

  static final Path RUNS = Path.of("evals/runs");

  /** The share of INVALID trials the validity gate tolerates unless told otherwise. */
  static final double VALIDITY_THRESHOLD = 0.2;

  private static final String BUDGET = "eval.budgetUsd must be a positive number of dollars";
  private static final String TRIALS = "eval.trials must be a whole number from 1";
  private static final String THRESHOLD = "eval.validityThreshold must be a number from 0 to 1";
  private static final String JUDGES = "eval.judges must be advisory or none";

  /** {@code null} budget means the settings' eval cap. */
  EvalOptions {
    if (budgetUsd != null && budgetUsd.signum() <= 0) {
      throw new IllegalArgumentException(BUDGET);
    }
    if (trials < 1) {
      throw new IllegalArgumentException(TRIALS);
    }
    if (!(validityThreshold >= 0 && validityThreshold <= 1)) {
      throw new IllegalArgumentException(THRESHOLD);
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
        Optional.ofNullable(property("eval.budgetUsd"))
            .map(value -> parsed(value, BigDecimal::new, BUDGET))
            .orElse(null),
        Optional.ofNullable(property("eval.trials"))
            .map(value -> parsed(value, Integer::valueOf, TRIALS))
            .orElse(1),
        Optional.ofNullable(property("eval.validityThreshold"))
            .map(value -> parsed(value, Double::valueOf, THRESHOLD))
            .orElse(VALIDITY_THRESHOLD),
        judges(Optional.ofNullable(property("eval.judges")).orElse("advisory")),
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

  /** Whether {@code value} asks for the advisory judges. */
  static boolean judges(String value) {
    return switch (value) {
      case "advisory" -> true;
      case "none" -> false;
      default -> throw new IllegalArgumentException(JUDGES);
    };
  }

  /** {@code value} read as a number, or the option's own message when it is not one. */
  private static <T> T parsed(String value, Function<String, T> read, String refusal) {
    try {
      return read.apply(value);
    } catch (NumberFormatException _) {
      throw new IllegalArgumentException(refusal);
    }
  }

  /** The property's value; null when unset or blank. */
  private static String property(String name) {
    String value = System.getProperty(name);
    return value == null || value.isBlank() ? null : value.strip();
  }
}
