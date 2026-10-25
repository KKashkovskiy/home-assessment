package com.enrola.anna.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The eval entry point: {@code evals/run.sh} runs it with {@code -Dtest=EvalSuiteTest} and the
 * {@code -Deval.*} properties {@link EvalOptions} reads; the normal build leaves it out. The run
 * gets a fresh database and data directory under {@code evals/runs/<id>/}, starting from the
 * owner's settings, one dynamic test per trial ({@code <id> #k}) and a last one for the gates, in
 * which a keyed run's judges are then asked. Only the gates, or a run that judged nothing, fail the
 * build, never a judge: a trial that did not pass is aborted with its reason, since a FAIL outside
 * the core set and the hard checks is reported, not gating. A crashed trial fails its test, and a
 * refused run or a selection no scenario matches fails the factory.
 */
@SpringBootTest
@Import(EvalConfig.class)
class EvalSuiteTest {

  private static final EvalOptions OPTIONS = EvalOptions.fromSystemProperties();

  /**
   * Only a keyed run gets a key: the product's OpenAI beans then run on the eval key, and a dry run
   * or an offline run has none, so not even the boot model check calls OpenAI.
   */
  @DynamicPropertySource
  static void runDirectory(DynamicPropertyRegistry registry) {
    AppState.copySettings(OPTIONS.dir());
    registry.add(
        "spring.datasource.url", () -> "jdbc:h2:file:" + OPTIONS.dir().toAbsolutePath() + "/db");
    registry.add("anna.data-dir", () -> OPTIONS.dir().toString());
    registry.add(
        "OPENAI_API_KEY",
        () -> OPTIONS.mode() instanceof RunMode.Keyed ? EvalKey.value().orElseThrow() : "");
  }

  @Autowired EvalRuns runs;

  @TestFactory
  Stream<DynamicTest> scenarios() throws IOException {
    EvalRun run = runs.prepare(OPTIONS);
    Stream<DynamicTest> trials =
        run.begin().stream()
            .flatMap(
                scenario ->
                    IntStream.rangeClosed(1, OPTIONS.trials())
                        .mapToObj(
                            trialNo ->
                                dynamicTest(
                                    scenario.id() + " #" + trialNo,
                                    () -> abortUnlessPassed(run.trial(scenario, trialNo)))));
    return Stream.concat(trials, Stream.of(dynamicTest("gates", () -> assertGreen(run.finish()))));
  }

  private static void abortUnlessPassed(Trial trial) {
    assumeTrue(trial.passed(), () -> trial.status() + " " + String.join(", ", trial.failedNames()));
  }

  private static void assertGreen(int exitCode) {
    assertEquals(0, exitCode, () -> "see " + OPTIONS.dir().resolve("report.md"));
  }
}
