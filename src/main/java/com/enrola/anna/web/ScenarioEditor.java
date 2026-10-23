package com.enrola.anna.web;

import com.enrola.anna.web.ScenarioForm.HistoryRow;
import com.enrola.anna.web.ScenarioForm.View;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.databind.node.ObjectNode;

/**
 * The scenario editor as the page shows it: the id it was opened as ({@code original}, empty for a
 * new scenario), the view shown, the YAML text, every field's text by its YAML path, the history
 * rows, and the problems a refused save met, with its notice. Each problem points at the field it
 * is about, so the page can mark and link it.
 */
public record ScenarioEditor(
    String original,
    View view,
    String yaml,
    Map<String, String> values,
    List<HistoryRow> history,
    List<String> problems,
    Notice notice) {

  /** The path a problem starts with: {@code expect.stage: ...}, {@code id must ...}. */
  private static final Pattern PATH = Pattern.compile("^([a-z_][\\w.]*)(?::| )");

  /** The places on the form a problem can point at: each field, and the groups the YAML has. */
  private static final Set<String> ANCHORS =
      Stream.concat(
              ScenarioForm.FIELDS.stream().map(ScenarioForm.Field::path),
              Stream.of("id", "kind", "lead", "seed", "history", "expect", "expect.every_reply"))
          .collect(Collectors.toUnmodifiableSet());

  public ScenarioEditor {
    values = Map.copyOf(values);
    history = List.copyOf(history);
    problems = List.copyOf(problems);
  }

  /** The editor filled from {@code tree}, the text {@code yaml} reads as. */
  static ScenarioEditor of(String original, View view, String yaml, ObjectNode tree) {
    return new ScenarioEditor(
        original,
        view,
        yaml,
        ScenarioForm.fieldTexts(tree),
        ScenarioForm.history(tree),
        List.of(),
        null);
  }

  /**
   * The field or group {@code problem} is about: the YAML path it starts with, cut back to the
   * nearest place on the form; null when it is about none.
   */
  public String fieldFor(String problem) {
    Matcher path = PATH.matcher(problem);
    if (!path.find()) {
      return null;
    }
    for (String place = path.group(1); !place.isEmpty(); place = parent(place)) {
      if (ANCHORS.contains(place)) {
        return place;
      }
    }
    return null;
  }

  /** Whether a problem the save met is about the field at {@code path}. */
  public boolean invalid(String path) {
    return problems.stream().anyMatch(problem -> path.equals(fieldFor(problem)));
  }

  public String value(String path) {
    return values.getOrDefault(path, "");
  }

  public String id() {
    return value("id");
  }

  public boolean isNew() {
    return original.isEmpty();
  }

  public boolean yamlView() {
    return view == View.YAML;
  }

  public boolean story() {
    return value("kind").equals("story");
  }

  /** An inline lead record has no field on the form; the YAML view edits it. */
  public boolean inlineLead() {
    return value("lead").equals(ScenarioForm.INLINE_LEAD);
  }

  public List<String> leads() {
    return ScenarioForm.LEADS;
  }

  /** The scenario can run from here once it is saved under the id shown. */
  public boolean runnable() {
    return !isNew() && original.equals(id());
  }

  ScenarioEditor withNotice(Notice shown) {
    return new ScenarioEditor(original, view, yaml, values, history, problems, shown);
  }

  ScenarioEditor withProblems(List<String> found, Notice shown) {
    return new ScenarioEditor(original, view, yaml, values, history, found, shown);
  }

  private static String parent(String path) {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? "" : path.substring(0, dot);
  }
}
