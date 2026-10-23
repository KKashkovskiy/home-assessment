package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.web.ScenarioForm.View;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Each problem a save meets points at the nearest place on the form, or at none. */
class ScenarioEditorTest {

  static final ScenarioEditor EDITOR =
      new ScenarioEditor("", View.FORM, "", Map.of(), List.of(), List.of(), null);

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      nullValues = "-",
      value = {
        "message: required property 'message' not found          | message",
        "expect.stage: does not have a value in the enumeration   | expect.stage",
        "expect.reply_match: property 'reply_match' is not defined | expect",
        "history.0.agent: must be at least 1 characters long      | history",
        "expect.every_reply.max_chars: must be at most 320        | expect.every_reply.max_chars",
        "id must equal the file name (turn-y)                     | id",
        "config.moderation.threat: must be at most 1              | -",
        "not YAML: mapping values are not allowed here            | -",
      })
  void aProblemPointsAtItsField(String problem, String field) {
    assertThat(EDITOR.fieldFor(problem)).isEqualTo(field);
  }
}
