package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Who judges a run, decided once from the options, the mode and the judge's model. */
class JudgeModeTest {

  static final EvalModels.Model PRICED = ScenarioFixtures.model("gpt-5.1");
  static final EvalModels.Model UNPRICED = ScenarioFixtures.model("gpt-5-mini");

  static Stream<Arguments> modes() {
    return Stream.of(
        Arguments.of(new RunMode.Keyed("eval"), PRICED, new JudgeMode.Asked("gpt-5.1")),
        Arguments.of(new RunMode.DryRun(), PRICED, new JudgeMode.Estimated("gpt-5.1")),
        Arguments.of(new RunMode.Offline(), PRICED, new JudgeMode.None("offline")),
        Arguments.of(new RunMode.NoKey(), PRICED, new JudgeMode.None("no key")),
        Arguments.of(
            new RunMode.Keyed("eval"),
            UNPRICED,
            new JudgeMode.None("judge model gpt-5-mini has no price")),
        Arguments.of(
            new RunMode.DryRun(),
            UNPRICED,
            new JudgeMode.None("judge model gpt-5-mini has no price")));
  }

  /** An unpriced judge model means no judge, in a dry run too, never a refused run. */
  @ParameterizedTest
  @MethodSource("modes")
  void judgesAreAdvisoryByMode(RunMode mode, EvalModels.Model judge, JudgeMode expected) {
    assertThat(JudgeMode.of(true, mode, judge)).isEqualTo(expected);
  }

  @Test
  void judgesNoneAsksNoJudge() {
    assertThat(JudgeMode.of(false, new RunMode.Keyed("eval"), PRICED))
        .isEqualTo(new JudgeMode.None("--judges none"));
  }
}
