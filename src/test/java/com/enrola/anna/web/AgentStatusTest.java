package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.ModelCheck;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** With a key set, the banner follows the boot check. */
class AgentStatusTest {

  @ParameterizedTest
  @CsvSource({"PENDING, CHECKING", "PASSED, READY", "FAILED, MODEL_FAILED"})
  void statusFollowsTheBootCheck(ModelCheck.Result check, AgentStatus status) {
    assertThat(AgentStatus.after(check)).isEqualTo(status);
  }
}
