package com.enrola.anna.eval;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Which scenarios a run takes. {@code ids} (comma-separated) wins over the rest, and an id no file
 * has fails the run; otherwise the id glob, the tag, the kind and the priority tag all apply, and
 * filters no scenario matches fail it too. Null means no filter.
 */
record Selection(String ids, String glob, String tag, String kind, String priority) {

  static final Selection ALL = new Selection(null, null, null, null, null);

  Selection {
    if (kind != null && !kind.equals("turn") && !kind.equals("story")) {
      throw new IllegalArgumentException("eval.kind must be turn or story");
    }
  }

  List<Scenario> apply(List<Scenario> all) {
    if (ids != null) {
      return byIds(all);
    }
    List<Scenario> selected = all.stream().filter(filters()).toList();
    if (selected.isEmpty()) {
      throw new ScenarioException("no scenario matches " + describe());
    }
    return selected;
  }

  /** The report header's words for it: "tag core, kind turn", or "all". */
  String describe() {
    List<String> parts = new ArrayList<>();
    add(parts, "scenarios", ids);
    add(parts, "filter", glob);
    add(parts, "tag", tag);
    add(parts, "kind", kind);
    add(parts, "priority", priority);
    return parts.isEmpty() ? "all" : String.join(", ", parts);
  }

  private List<Scenario> byIds(List<Scenario> all) {
    Set<String> wanted =
        Arrays.stream(ids.split(",")).map(String::strip).collect(Collectors.toSet());
    Set<String> known = all.stream().map(Scenario::id).collect(Collectors.toSet());
    List<String> unknown = wanted.stream().filter(id -> !known.contains(id)).sorted().toList();
    if (!unknown.isEmpty()) {
      throw new ScenarioException("no scenario file for " + String.join(", ", unknown));
    }
    return all.stream().filter(scenario -> wanted.contains(scenario.id())).toList();
  }

  private Predicate<Scenario> filters() {
    PathMatcher idGlob =
        glob == null ? null : FileSystems.getDefault().getPathMatcher("glob:" + glob);
    return scenario ->
        (idGlob == null || idGlob.matches(Path.of(scenario.id())))
            && (tag == null || scenario.tags().contains(tag))
            && (kind == null || scenario.kind().equals(kind))
            && (priority == null || scenario.tags().contains(priority));
  }

  private static void add(List<String> parts, String name, String value) {
    if (value != null) {
      parts.add(name + " " + value);
    }
  }
}
