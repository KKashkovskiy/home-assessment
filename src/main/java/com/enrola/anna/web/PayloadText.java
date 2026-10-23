package com.enrola.anna.web;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Trace payload values in words for the inspector, whatever their shape, so a payload a writer
 * gains shows without a new rule. Writers keep no key order, so keys are sorted.
 */
final class PayloadText {

  /** What an absent or empty value reads as. */
  static final String NONE = "none";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private PayloadText() {}

  /**
   * One line of {@code key: value} pairs, keys in words, then nested objects flattened the same
   * way; empty values are left out: {@code chars: 212, limit: 320}.
   */
  static String line(JsonNode payload) {
    if (!payload.isObject()) {
      return value(payload);
    }
    List<Map.Entry<String, JsonNode>> fields =
        payload.properties().stream().sorted(Map.Entry.comparingByKey()).toList();
    StringJoiner line = new StringJoiner(", ");
    fields.stream()
        .filter(field -> !field.getValue().isObject() && !blank(field.getValue()))
        .forEach(
            field -> line.add(field.getKey().replace('_', ' ') + ": " + value(field.getValue())));
    fields.stream()
        .filter(field -> field.getValue().isObject())
        .map(field -> line(field.getValue()))
        .filter(nested -> !nested.isEmpty())
        .forEach(line::add);
    return line.toString();
  }

  /**
   * A tool call's arguments one {@code date_from = 2026-07-27} to a line, in the order the model
   * wrote them, or the text itself when it is not a JSON object.
   */
  static String arguments(String argumentsJson) {
    JsonNode arguments;
    try {
      arguments = JSON.readTree(argumentsJson);
    } catch (JacksonException e) {
      return argumentsJson;
    }
    if (!arguments.isObject()) {
      return argumentsJson;
    }
    StringJoiner lines = new StringJoiner("\n");
    arguments
        .properties()
        .forEach(field -> lines.add(field.getKey() + " = " + value(field.getValue())));
    return lines.toString();
  }

  /** Text bare, fractions to three places, arrays comma-separated, null or empty as "none". */
  static String value(JsonNode value) {
    if (value.isMissingNode() || value.isNull() || (value.isArray() && value.isEmpty())) {
      return NONE;
    }
    if (value.isArray()) {
      List<String> items = new ArrayList<>();
      value.forEach(item -> items.add(value(item)));
      return String.join(", ", items);
    }
    if (value.isNumber()) {
      return value
          .decimalValue()
          .setScale(3, RoundingMode.HALF_UP)
          .stripTrailingZeros()
          .toPlainString();
    }
    return value.asString();
  }

  private static boolean blank(JsonNode value) {
    return value.isNull()
        || (value.isArray() && value.isEmpty())
        || (value.isString() && value.stringValue().isEmpty());
  }
}
