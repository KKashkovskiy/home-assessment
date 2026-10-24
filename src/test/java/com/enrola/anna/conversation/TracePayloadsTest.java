package com.enrola.anna.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.guard.PreGuards;
import com.enrola.anna.scheduling.ToolResult;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The payload records the trace writes read back as written, through a mapper configured like the
 * application's, so the writer and the inspector cannot drift apart on a name or a type.
 */
class TracePayloadsTest {

  static final JsonMapper JSON =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

  @Test
  void modelRoundReadsBackWithItsWireNames() {
    ModelRoundPayload round =
        new ModelRoundPayload(
            "gpt-5.1",
            "low",
            2,
            ModelRoundPayload.Call.REPAIR,
            new ModelResponse.Usage(100, 40, 20, 5),
            new BigDecimal("0.0037"),
            812,
            ModelRoundPayload.Status.FAILED,
            ModelResponse.Failure.TIMEOUT,
            null);

    String written = JSON.writeValueAsString(round);

    assertThat(written)
        .contains(
            "\"cost_usd\":0.0037",
            "\"latency_ms\":812",
            "\"call\":\"REPAIR\"",
            "\"status\":\"FAILED\"",
            "\"failure\":\"TIMEOUT\"");
    assertThat(JSON.readValue(written, ModelRoundPayload.class)).isEqualTo(round);
  }

  @Test
  void toolCallErrorAndOverrideReadBack() {
    ToolCallPayload call =
        new ToolCallPayload(
            "{\"date_from\":\"2026-07-27\"}",
            "{\"slots\":[]}",
            0,
            new ToolResult.Filtering(22, 16, 4, 0),
            3);
    ErrorPayload error = new ErrorPayload("TIMEOUT", "OpenAIIoException", 3);
    OverridePayload override = new OverridePayload("BOOKED", "NONE");

    assertThat(JSON.readValue(JSON.writeValueAsString(call), ToolCallPayload.class))
        .isEqualTo(call);
    assertThat(JSON.writeValueAsString(call))
        .contains("\"slot_count\":0", "\"outside_lead_hours\":4");
    assertThat(JSON.readValue(JSON.writeValueAsString(error), ErrorPayload.class)).isEqualTo(error);
    assertThat(JSON.readValue(JSON.writeValueAsString(override), OverridePayload.class))
        .isEqualTo(override);
  }

  /**
   * A deciding step's outcome reads back as the record it was, under the shape the combined row of
   * older turns had too; a passing step's payload is empty.
   */
  @Test
  void preGuardStepReadsBackAsItsOutcome() {
    PreGuardPayload decided = new PreGuardPayload(new PreGuards.Silence(EndReason.ABUSIVE));
    PreGuardPayload passed = new PreGuardPayload(null);

    String written = JSON.writeValueAsString(decided);

    assertThat(JSON.readTree(written))
        .isEqualTo(
            JSON.readTree("{\"outcome\":\"Silence\",\"detail\":{\"end_reason\":\"ABUSIVE\"}}"));
    assertThat(JSON.readValue(written, PreGuardPayload.class)).isEqualTo(decided);
    assertThat(JSON.writeValueAsString(passed)).isEqualTo("{}");
    assertThat(JSON.readValue("{}", PreGuardPayload.class)).isEqualTo(passed);
  }

  /** The model's self-harm check reads back with the intent it read, or the failure instead. */
  @Test
  void selfHarmCheckReadsBackWithoutTheFieldItLacks() {
    SelfHarmCheckPayload read = SelfHarmCheckPayload.read(LeadIntent.SELF_HARM);
    SelfHarmCheckPayload failed = SelfHarmCheckPayload.failed(ModelResponse.Failure.TIMEOUT);

    assertThat(JSON.writeValueAsString(read))
        .isEqualTo("{\"detector\":\"MODEL_CLASSIFY\",\"lead_intent\":\"SELF_HARM\"}");
    assertThat(JSON.writeValueAsString(failed))
        .isEqualTo("{\"detector\":\"MODEL_CLASSIFY\",\"failure\":\"TIMEOUT\"}");
    assertThat(JSON.readValue(JSON.writeValueAsString(read), SelfHarmCheckPayload.class))
        .isEqualTo(read);
    assertThat(JSON.readValue(JSON.writeValueAsString(failed), SelfHarmCheckPayload.class))
        .isEqualTo(failed);
  }

  /** The writer's values are enums, sets and counts; they read back as their wire values. */
  @Test
  void stateDiffReadsBackAsWireValues() {
    Map<String, StateChange> written = new LinkedHashMap<>();
    written.put("stage", new StateChange(Stage.ENGAGE, Stage.PREFERENCE));
    written.put("flags", new StateChange(EnumSet.noneOf(Flag.class), EnumSet.of(Flag.OBJECTION)));
    written.put("agent_turns", new StateChange(0, 1));

    Map<String, StateChange> read =
        JSON.readValue(JSON.writeValueAsString(written), new TypeReference<>() {});

    assertThat(read)
        .containsExactly(
            Map.entry("stage", new StateChange("ENGAGE", "PREFERENCE")),
            Map.entry("flags", new StateChange(List.of(), List.of("OBJECTION"))),
            Map.entry("agent_turns", new StateChange(0, 1)));
  }
}
