package com.enrola.anna.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.enrola.anna.config.AgentProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A failed boot check warns, unless strict mode asks for a failed boot; either way the console can
 * read how it went.
 */
class ModelCheckTest {

  final ScriptedModelClient model = new ScriptedModelClient();

  @Test
  void checkPingsTheConfiguredModel() {
    model.enqueue(
        new ModelResponse.Decided(
            JsonNodeFactory.instance.objectNode().put("ok", true),
            null,
            ModelResponse.Usage.ZERO,
            5,
            "{}",
            "{}"));

    ModelCheck check = new ModelCheck(model, agent(false));
    assertThat(check.result()).isEqualTo(ModelCheck.Result.PENDING);

    check.run(null);

    assertThat(model.calls()).singleElement().extracting(ModelRequest::model).isEqualTo("gpt-5.1");
    assertThat(check.result()).isEqualTo(ModelCheck.Result.PASSED);
  }

  @Test
  void failedCheckWarnsAndContinues() {
    model.enqueue(Decisions.failed(ModelResponse.Failure.HTTP_5XX));
    ModelCheck check = new ModelCheck(model, agent(false));

    assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    assertThat(check.result()).isEqualTo(ModelCheck.Result.FAILED);
  }

  @Test
  void strictModeFailsBoot() {
    model.enqueue(Decisions.failed(ModelResponse.Failure.HTTP_4XX));

    assertThatIllegalStateException()
        .isThrownBy(() -> new ModelCheck(model, agent(true)).run(null))
        .withMessage("model check failed for gpt-5.1: HTTP_4XX");
  }

  private static AgentProperties agent(boolean strict) {
    return new AgentProperties("gpt-5.1", "low", "auto", Duration.ofSeconds(1), 0, 16, 4, strict);
  }
}
