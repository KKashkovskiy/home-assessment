package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/** Payloads of every shape the writers use, in words. */
class PayloadTextTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "keys sorted, empty values left out | {\"notes\":[],\"limit\":320,\"chars\":212} "
            + "| chars: 212, limit: 320",
        "nested object after the fields | {\"detail\":{\"reason\":\"KEYWORD\"},"
            + "\"outcome\":\"Unsubscribe\"} | outcome: Unsubscribe, reason: KEYWORD",
        "keys in words, arrays joined | {\"lead_intent\":\"OPT_OUT\",\"notes\":[\"a\",\"b\"]} "
            + "| lead intent: OPT_OUT, notes: a, b",
        "fractions to three places | {\"self_harm\":0.29749,\"threat\":1.2E-5} "
            + "| self harm: 0.297, threat: 0",
        "a bare string | \"first\" | first",
        "nothing to say | {\"notes\":[],\"detail\":\"\",\"skipped\":null} | ''"
      })
  void lineReadsAnyPayload(String name, String payload, String line) {
    assertThat(PayloadText.line(JSON.readTree(payload))).isEqualTo(line);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "in the model's order, one to a line | {\"date_to\":\"2026-07-27\","
            + "\"date_from\":\"2026-07-26\"} | date_to = 2026-07-27\\ndate_from = 2026-07-26",
        "not JSON | monday please | monday please",
        "not an object | [1,2] | [1,2]"
      })
  void argumentsReadAsSentWhenTheyAreNotAnObject(String name, String json, String arguments) {
    assertThat(PayloadText.arguments(json)).isEqualTo(arguments.replace("\\n", "\n"));
  }
}
