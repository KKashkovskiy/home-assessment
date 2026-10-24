package com.enrola.anna.web;

import com.enrola.anna.config.Settings;
import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptVersion;
import com.enrola.anna.web.ConfigurationPage.VersionRow;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Assembles the {@link ConfigurationPage} from {@link PromptStore}: every version with what stops
 * it loading under the persona it is handed, the one asked for or else the active one, and the form
 * holding its texts or a refused save's. It also words the page's notices, so the controller only
 * maps outcomes to statuses.
 */
@Component
public class ConfigurationPages {

  /** The longest item a notice lists: a store error can quote posted text, a tool's name say. */
  static final int ITEM_MAX = 160;

  static final Notice SAVE_RACE =
      new Notice(
          Notice.Level.FAIL,
          "SAVE_RACE",
          "409 SAVE_RACE: another save took the next version number first, so this one was not"
              + " saved. The list is reloaded and your edits are still here; save again.");

  static final Notice NOT_FOUND =
      new Notice(
          Notice.Level.FAIL, "NOT_FOUND", "404 NOT_FOUND: that prompt version does not exist.");

  static final Notice BAD_REQUEST =
      new Notice(
          Notice.Level.FAIL,
          "BAD_REQUEST",
          "400 BAD_REQUEST: the request had a value this page does not take, so here is the active"
              + " version.");

  private final PromptStore prompts;

  public ConfigurationPages(PromptStore prompts) {
    this.prompts = prompts;
  }

  /**
   * The page on the stored texts of {@code requested}, or of the active version when no version has
   * that id; when the version cannot load, the notice says why.
   */
  public ConfigurationPage stored(String requested, PromptFile file, Settings.Persona persona) {
    List<PromptVersion> versions = prompts.all();
    PromptVersion shown = select(versions, requested);
    List<VersionRow> rows = rows(versions, persona);
    VersionRow selected = rows.get(versions.indexOf(shown));
    return new ConfigurationPage(
        rows, selected, VersionForm.of(shown, file), false, unloadable(selected));
  }

  /** The page with a save's texts kept in the form, beside the version they started from. */
  public ConfigurationPage unsaved(VersionForm form, Notice notice, Settings.Persona persona) {
    List<PromptVersion> versions = prompts.all();
    List<VersionRow> rows = rows(versions, persona);
    VersionRow base = rows.get(versions.indexOf(select(versions, form.base())));
    return new ConfigurationPage(rows, base, form, true, notice);
  }

  /** {@code requested} when a version has that id, else the active one, so a stale link opens. */
  static PromptVersion select(List<PromptVersion> versions, String requested) {
    return versions.stream()
        .filter(version -> version.id().equals(requested))
        .findFirst()
        .or(() -> versions.stream().filter(PromptVersion::active).findFirst())
        .orElseThrow(() -> new IllegalStateException("no active prompt version"));
  }

  /** Saved, with the store's warnings and a cut note, if any, one per line. */
  Notice saved(PromptStore.SaveResult.Saved saved, VersionForm form) {
    String text =
        "Saved %s. %s stays active until you activate %s."
            .formatted(saved.id(), prompts.activeId(), saved.id());
    List<String> warnings = new ArrayList<>(saved.warnings());
    if (form.noteCut()) {
      warnings.add("the note was cut to its first " + VersionForm.NOTE_MAX + " characters");
    }
    return warnings.isEmpty()
        ? new Notice(Notice.Level.PASS, "SAVED", text)
        : new Notice(Notice.Level.WARN, "SAVED", text + "\nWarnings:" + lines(warnings));
  }

  static Notice notSaved(PromptStore.SaveResult.Rejected rejected) {
    String warnings =
        rejected.warnings().isEmpty() ? "" : "\nWarnings:" + lines(rejected.warnings());
    return new Notice(
        Notice.Level.FAIL, "NOT_SAVED", "Not saved:" + lines(rejected.errors()) + warnings);
  }

  static Notice activated(String id) {
    return new Notice(
        Notice.Level.INFO,
        "ACTIVATED",
        id
            + " is now active. New conversations start on it; running ones keep their pinned version.");
  }

  /** Activation refused: the page is on the version that does not load. */
  Notice loadFailed(ConfigurationPage page) {
    return new Notice(
        Notice.Level.FAIL,
        "LOAD_FAILED",
        "409 LOAD_FAILED: %s cannot be loaded, so %s stays active:%s"
            .formatted(
                page.selected().id(), prompts.activeId(), lines(page.selected().loadErrors())));
  }

  /** Why the selected version's Activate is off, or null when it loads. */
  private static Notice unloadable(VersionRow row) {
    if (row.loads()) {
      return null;
    }
    return new Notice(
        Notice.Level.WARN,
        "UNLOADABLE",
        "%s cannot be loaded under this configuration, so it cannot be activated; save a fixed copy as a new version:%s"
            .formatted(row.id(), lines(row.loadErrors())));
  }

  private List<VersionRow> rows(List<PromptVersion> versions, Settings.Persona persona) {
    // ponytail: every version is validated on each page view; there are tens, not thousands
    return versions.stream()
        .map(
            version ->
                new VersionRow(
                    version.id(),
                    version.createdAt(),
                    version.createdBy(),
                    version.note(),
                    version.active(),
                    prompts.loadErrors(version, persona)))
        .toList();
  }

  /** Each item on a line of its own, cut to {@link #ITEM_MAX}. */
  private static String lines(List<String> items) {
    return items.stream().map(item -> "\n" + clip(item)).collect(Collectors.joining());
  }

  static String clip(String item) {
    if (item.length() <= ITEM_MAX) {
      return item;
    }
    int end = Character.isHighSurrogate(item.charAt(ITEM_MAX - 2)) ? ITEM_MAX - 2 : ITEM_MAX - 1;
    return item.substring(0, end) + "…";
  }
}
