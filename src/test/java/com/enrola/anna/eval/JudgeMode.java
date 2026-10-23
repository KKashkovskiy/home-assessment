package com.enrola.anna.eval;

/**
 * Who answers a run's judge questions, decided once from the options, the run's mode and the
 * judge's model: a keyed run asks them, a dry run prices them, any other run has none, and the
 * header says why. A judge model the price table cannot price means no judge, never a refused run.
 */
sealed interface JudgeMode {

  /** A keyed run: the judge on {@code model} answers each question while the cap has room. */
  record Asked(String model) implements JudgeMode {}

  /** A dry run: the questions are priced on {@code model}; none is asked. */
  record Estimated(String model) implements JudgeMode {}

  /** No judge, for the reason {@code why} gives. */
  record None(String why) implements JudgeMode {}

  static JudgeMode of(boolean wanted, RunMode mode, EvalModels.Model judge) {
    if (!wanted) {
      return new None("--judges none");
    }
    return switch (mode) {
      case RunMode.Offline _ -> new None("offline");
      case RunMode.NoKey _ -> new None("no key");
      case RunMode.DryRun _, RunMode.Keyed _ when !judge.priced() ->
          new None("judge model " + judge.name() + " has no price");
      case RunMode.DryRun _ -> new Estimated(judge.name());
      case RunMode.Keyed _ -> new Asked(judge.name());
    };
  }

  /** The judge's model when it is asked or priced; null when there is none. */
  default String judgeModel() {
    return switch (this) {
      case Asked asked -> asked.model();
      case Estimated estimated -> estimated.model();
      case None _ -> null;
    };
  }
}
