package com.enrola.anna.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.enrola.anna.config.AgentProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/** A failed boot check warns, unless strict mode asks for a failed boot. */
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

    new ModelCheck(model, agent(false)).run(null);

    assertThat(model.calls()).singleElement().extracting(ModelRequest::model).isEqualTo("gpt-5.1");
  }

  @Test
  void failedCheckWarnsAndContinues() {
    model.enqueue(Decisions.failed(ModelResponse.Failure.HTTP_5XX));

    assertThatCode(() -> new ModelCheck(model, agent(false)).run(null)).doesNotThrowAnyException();
  }

  @Test
  void strictModeFailsBoot() {
    model.enqueue(Decisions.failed(ModelResponse.Failure.HTTP_4XX));

    assertThatIllegalStateException()
        .isThrownBy(() -> new ModelCheck(model, agent(true)).run(null))
        .withMessage("model check failed for gpt-5.1: HTTP_4XX");
  }

  private static AgentProperties agent(boolean strict) {
    return new AgentProperties("gpt-5.1", "low", "auto", Duration.ofSeconds(1), 0, 16, strict);
  }
}
