package com.enrola.anna.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The decision record and the shipped schema file describe the same object; the file wins. */
class DecisionSchemaTest {

  static final JsonNode SCHEMA = schema();

  @Test
  void requiredKeysMatchRecord() {
    JsonNode serialised = ModelResponse.Decided.JSON.valueToTree(Decisions.engaged().build());

    assertThat(serialised.propertyNames())
        .containsExactlyInAnyOrderElementsOf(strings(SCHEMA.get("required")));
    assertThat(serialised.get("extracted").propertyNames())
        .containsExactlyInAnyOrderElementsOf(strings(SCHEMA.at("/properties/extracted/required")));
  }

  static Stream<Arguments> enums() {
    return Stream.of(
        Arguments.of("/properties/lead_intent/enum", LeadIntent.values()),
        Arguments.of("/properties/stage/enum", Stage.values()),
        Arguments.of("/properties/objection_type/enum", ObjectionType.values()),
        Arguments.of("/properties/end_reason/enum", EndReason.values()),
        Arguments.of(
            "/properties/extracted/properties/coverage_goal/enum", Decision.CoverageGoal.values()),
        Arguments.of(
            "/properties/extracted/properties/cover_type/enum", Decision.CoverType.values()),
        Arguments.of(
            "/properties/extracted/properties/preferred_time_of_day/enum",
            Decision.TimeOfDay.values()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("enums")
  void enumsMatchSchema(String pointer, Enum<?>[] values) {
    assertThat(names(values)).isEqualTo(strings(SCHEMA.at(pointer)));
  }

  /**
   * Code adds its own flags and handoff reasons after the model's, so the schema's are a prefix.
   */
  @Test
  void modelFlagsAndHandoffReasonsLeadTheirEnums() {
    assertThat(names(Flag.values()))
        .startsWith(strings(SCHEMA.at("/properties/flags/items/enum")).toArray(String[]::new));
    assertThat(names(HandoffReason.values()))
        .startsWith(strings(SCHEMA.at("/properties/handoff_reason/enum")).toArray(String[]::new));
  }

  /** What the client hands over for each text, and decisions with a field missing or coerced. */
  static Stream<Arguments> notDecisions() {
    return Stream.of(
        Arguments.of("empty text", ModelResponse.Decided.JSON.readTree("")),
        Arguments.of("whitespace", ModelResponse.Decided.JSON.readTree("  \n ")),
        Arguments.of("null", ModelResponse.Decided.JSON.readTree("null")),
        Arguments.of("array", ModelResponse.Decided.JSON.readTree("[]")),
        Arguments.of("no flags", engaged().without("flags")),
        Arguments.of("no stage", engaged().without("stage")),
        Arguments.of("no extracted", engaged().without("extracted")),
        Arguments.of("no send_reply", engaged().without("send_reply")),
        Arguments.of("null stage", engaged().putNull("stage")),
        Arguments.of(
            "null flag", engaged().set("flags", ModelResponse.Decided.JSON.readTree("[null]"))),
        Arguments.of("null coverage_goal", withExtracted("coverage_goal", null)),
        Arguments.of("number for an enum", engaged().put("lead_intent", 0)),
        Arguments.of("string for a boolean", engaged().put("send_reply", "true")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("notDecisions")
  void outputThatIsNotADecisionIsAMismatch(String name, JsonNode output) {
    assertThatThrownBy(() -> decided(output).decision())
        .isInstanceOf(ModelResponse.SchemaMismatch.class);
  }

  @Test
  void unknownFlagRejected() {
    ObjectNode output = engaged();
    output.putArray("flags").add("NOT_A_FLAG");

    assertThatThrownBy(() -> decided(output).decision())
        .isInstanceOf(ModelResponse.SchemaMismatch.class)
        .hasMessageContaining("NOT_A_FLAG");
  }

  @Test
  void unknownPropertyRejected() {
    ObjectNode output = engaged();
    output.put("mood", "cheerful");

    assertThatThrownBy(() -> decided(output).decision())
        .isInstanceOf(ModelResponse.SchemaMismatch.class)
        .hasMessageContaining("mood");
  }

  private static ObjectNode engaged() {
    return ModelResponse.Decided.JSON.valueToTree(Decisions.engaged().build());
  }

  private static ObjectNode withExtracted(String field, String value) {
    ObjectNode output = engaged();
    ((ObjectNode) output.get("extracted")).put(field, value);
    return output;
  }

  private static ModelResponse.Decided decided(JsonNode output) {
    return new ModelResponse.Decided(
        output, null, ModelResponse.Usage.ZERO, 0, "{}", "{}", List.of());
  }

  private static JsonNode schema() {
    try {
      String file =
          new ClassPathResource("prompts/v1/agent-decision.schema.json")
              .getContentAsString(StandardCharsets.UTF_8);
      return ModelResponse.Decided.JSON.readTree(file).get("schema");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<String> strings(JsonNode array) {
    assertThat(array.isArray()).as("%s is an array", array).isTrue();
    return array.valueStream().map(JsonNode::stringValue).toList();
  }

  private static List<String> names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).toList();
  }
}
