package com.enrola.anna.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.awaitility.Awaitility.await;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.config.SettingsStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The check asks what a turn would ask; a failure at boot warns unless strict mode asks for a
 * failed boot, and a save that changes the request checks again. The console reads how the latest
 * check went.
 */
class ModelCheckTest {

  static final ModelResponse OK =
      new ModelResponse.Decided(
          JsonNodeFactory.instance.objectNode().put("ok", true),
          null,
          ModelResponse.Usage.ZERO,
          5,
          "{}",
          "{}");

  final ScriptedModelClient model = new ScriptedModelClient();

  @TempDir Path dataDir;

  /** The model, its reasoning and the output cap of the settings, as a turn sends them. */
  @Test
  void checkAsksWhatATurnWould() {
    model.enqueue(OK);
    ModelCheck check = check(false);
    assertThat(check.result()).isEqualTo(ModelCheck.Result.PENDING);

    check.run(null);

    ModelRequest ping = model.calls().getFirst();
    assertThat(ping.model()).isEqualTo("gpt-5.1");
    assertThat(ping.reasoningEffort()).isEqualTo("low");
    assertThat(ping.reasoningSummary()).isEqualTo("auto");
    assertThat(ping.maxOutputTokens()).isEqualTo(1200);
    assertThat(check.result()).isEqualTo(ModelCheck.Result.PASSED);
  }

  @Test
  void failedCheckWarnsAndContinues() {
    model.enqueue(Decisions.failed(ModelResponse.Failure.HTTP_5XX));
    ModelCheck check = check(false);

    assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    assertThat(check.result()).isEqualTo(ModelCheck.Result.FAILED);
  }

  @Test
  void strictModeFailsBoot() {
    model.enqueue(Decisions.failed(ModelResponse.Failure.HTTP_4XX));

    assertThatIllegalStateException()
        .isThrownBy(() -> check(true).run(null))
        .withMessage("model check failed for gpt-5.1: HTTP_4XX");
  }

  /** A save that changes the request asks again; one that changes something else does not. */
  @Test
  void aSaveThatChangesTheRequestChecksAgain() {
    ModelCheck check = new ModelCheck(model, SettingsFixture.yml());
    SettingsStore settings =
        new SettingsStore(SettingsFixture.DEFAULTS, dataDir, List.of(check), List.of());
    model.enqueue(OK).enqueue(Decisions.failed(ModelResponse.Failure.HTTP_4XX));
    check.run(null);

    settings.save(SettingsFixture.with(settings.current(), "limits", "maxTurns", 7));
    assertThat(model.calls()).hasSize(1);

    settings.save(
        SettingsFixture.with(
            settings.current(), "agent", "reasoning", Settings.ReasoningEffort.MEDIUM));
    await().atMost(Duration.ofSeconds(5)).until(() -> check.result() == ModelCheck.Result.FAILED);
    assertThat(model.calls()).last().extracting(ModelRequest::reasoningEffort).isEqualTo("medium");
  }

  /** A check that heard the boot record from a store, as at boot. */
  private ModelCheck check(boolean strict) {
    ModelCheck check =
        new ModelCheck(model, SettingsFixture.yml("anna.agent.strict-model-check=" + strict));
    new SettingsStore(SettingsFixture.DEFAULTS, dataDir, List.of(check), List.of());
    return check;
  }
}
