package com.enrola.anna.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
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
 * owner's settings, and one dynamic test per scenario it runs. A FAIL or INVALID trial fails its
 * test, a skipped one is aborted, and a refused or crashed run fails the factory.
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
    return run.begin().stream()
        .map(scenario -> dynamicTest(scenario.id(), () -> assertPassed(run.trial(scenario))))
        .onClose(run::finish);
  }

  private static void assertPassed(Trial trial) {
    assumeFalse(trial.status().skipped(), trial.status()::name);
    assertEquals(TrialStatus.PASS, trial.status(), () -> String.join(", ", trial.failedNames()));
  }
}
