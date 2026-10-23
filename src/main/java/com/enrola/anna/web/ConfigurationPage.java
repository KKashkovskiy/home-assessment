package com.enrola.anna.web;

import java.time.Instant;
import java.util.List;

/**
 * Everything the Configuration page renders: every prompt version, oldest first, with what stops it
 * loading, the selected one, the editor's form and a notice or null. The form holds the selected
 * version's texts, or, when {@code unsaved}, a refused save's texts kept so nothing typed is lost.
 */
public record ConfigurationPage(
    List<VersionRow> versions,
    VersionRow selected,
    VersionForm form,
    boolean unsaved,
    Notice notice) {

  /** One version in the list; {@code loadErrors} say why a turn could not load it. */
  public record VersionRow(
      String id,
      Instant createdAt,
      String createdBy,
      String note,
      boolean active,
      List<String> loadErrors) {

    public VersionRow {
      loadErrors = List.copyOf(loadErrors);
    }

    public boolean loads() {
      return loadErrors.isEmpty();
    }
  }

  public ConfigurationPage {
    versions = List.copyOf(versions);
  }

  /** The list's and the diff pickers' order: the latest work at the top. */
  public List<VersionRow> newestFirst() {
    return versions.reversed();
  }

  public boolean isSelected(VersionRow row) {
    return row.id().equals(selected.id());
  }

  /**
   * Only a stored version that loads and is not active yet; a refused save's texts are not the
   * selected version's, so activating from them would mislead.
   */
  public boolean canActivate() {
    return !unsaved && !selected.active() && selected.loads();
  }

  /** A diff needs two versions. */
  public boolean canDiff() {
    return versions.size() > 1;
  }

  /**
   * The diff's left side until the designer picks: the version before the selected one, so the diff
   * shows what the selected one changed; for the first version, the one after it.
   */
  public VersionRow diffLeft() {
    int at = versions.indexOf(selected);
    return versions.get(at > 0 ? at - 1 : Math.min(1, versions.size() - 1));
  }

  /** The note field's limit, the width of the column it is stored in. */
  public int noteMax() {
    return VersionForm.NOTE_MAX;
  }

  ConfigurationPage withNotice(Notice shown) {
    return new ConfigurationPage(versions, selected, form, unsaved, shown);
  }
}
