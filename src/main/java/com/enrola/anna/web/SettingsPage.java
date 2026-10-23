package com.enrola.anna.web;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The runtime settings section of the Configuration page: the form, what refused its last save,
 * keyed by field ({@code prices[i]} for a price row), what was typed in each field that did not
 * bind, a notice or null, and what the form cannot change: the values read once at boot and whether
 * each key is set.
 */
public record SettingsPage(
    SettingsForm form,
    Map<String, String> problems,
    Map<String, String> typed,
    Notice notice,
    List<RestartOnly> restartOnly,
    List<Key> keys) {

  /** A value that needs a restart, as the server runs with it. */
  public record RestartOnly(String name, String value) {}

  /** An environment key, by name, and only whether it is set. */
  public record Key(String name, boolean set) {}

  public SettingsPage {
    problems = Map.copyOf(problems);
    typed = Map.copyOf(typed);
    restartOnly = List.copyOf(restartOnly);
    keys = List.copyOf(keys);
  }

  public boolean invalid(String field) {
    return problems.containsKey(field);
  }

  /** What {@code field} shows: what was typed when it did not bind, else {@code value}. */
  public Object shown(String field, Object value) {
    return typed.containsKey(field) ? typed.get(field) : value;
  }

  /** The ids describing a control: its problem's, then its hint's; null when it has neither. */
  public String describedBy(String field, String id, boolean hinted) {
    String ids =
        Stream.of(invalid(field) ? id + "-error" : null, hinted ? id + "-hint" : null)
            .filter(Objects::nonNull)
            .collect(Collectors.joining(" "));
    return ids.isEmpty() ? null : ids;
  }

  /** The name price row {@code row}'s cells post under, and its problems' key. */
  public String rowField(int row) {
    return SettingsForm.rowField(row);
  }

  /** The first price row's problem, shown under the table; null when every row is fine. */
  public String priceProblem() {
    for (int row = 0; row < form.priceRows().size(); row++) {
      String problem = problems.get(SettingsForm.rowField(row));
      if (problem != null) {
        return problem;
      }
    }
    return null;
  }

  /** The models the price table prices, which the model field suggests. */
  public List<String> models() {
    return form.priceRows().stream()
        .filter(row -> !row.blank())
        .map(SettingsForm.PriceRow::model)
        .toList();
  }

  SettingsPage withNotice(Notice shown) {
    return new SettingsPage(form, problems, typed, shown, restartOnly, keys);
  }
}
