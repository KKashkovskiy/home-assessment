package com.enrola.anna.prompt;

import com.enrola.anna.AppClock;
import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills the version table from the repo's {@code prompts/vN/} directories at boot. It runs as the
 * bean is built, after the migrations and before the server accepts a request, so no request finds
 * the table empty or the active version unloadable.
 */
@Component
class PromptSeed {

  private static final Logger log = LoggerFactory.getLogger(PromptSeed.class);

  private final PromptVersionRepository versions;
  private final PromptLoader loader;
  private final PromptStore store;
  private final SettingsStore settings;
  private final AppClock clock;
  private final TransactionTemplate tx;

  PromptSeed(
      PromptVersionRepository versions,
      PromptLoader loader,
      PromptStore store,
      SettingsStore settings,
      AppClock clock,
      TransactionTemplate tx) {
    this.versions = versions;
    this.loader = loader;
    this.store = store;
    this.settings = settings;
    this.clock = clock;
    this.tx = tx;
  }

  /**
   * In one transaction: inserts every repo version that has no row, activates the lowest when none
   * is active, and loads the active one, all with the yml's persona. A repo version with any
   * finding fails the boot, since the repo and the yml are under the owner's control, and so does
   * an active version the yml's persona cannot load. An id the table already has is skipped, with a
   * warning when the row was a save: the repo and the table then disagree. Then the settings file
   * is checked against the versions, and set aside when its persona does not fit.
   */
  @PostConstruct
  void seed() {
    Settings.Persona yml = settings.defaults().persona();
    tx.executeWithoutResult(
        _ -> {
          warnIfDamaged();
          loader.availableVersions().forEach(id -> seedIfAbsent(id, yml));
          activateLowestIfNoneActive();
          store.bundle(store.activeId(), yml);
        });
    settings.recheck();
  }

  private void seedIfAbsent(String id, Settings.Persona persona) {
    versions
        .findById(id)
        .ifPresentOrElse(
            PromptSeed::warnIfSaved, () -> versions.save(validRepoVersion(id, persona)));
  }

  private PromptVersion validRepoVersion(String id, Settings.Persona persona) {
    PromptVersion version = loader.load(id, clock.now());
    PromptValidation.Result check = PromptValidation.validate(version, persona);
    if (!check.clean()) {
      throw new IllegalStateException(
          "prompts/"
              + id
              + " is invalid: errors "
              + check.errors()
              + ", warnings "
              + check.warnings());
    }
    log.info("prompt version {} seeded from prompts/{}", id, id);
    return version;
  }

  private static void warnIfSaved(PromptVersion existing) {
    if (!PromptVersion.REPO.equals(existing.createdBy())) {
      log.warn(
          "prompt version {} is both prompts/{} in the repo and a save; the table's row runs",
          existing.id(),
          existing.id());
    }
  }

  /** Rows with none active is damage done by hand: an empty table is the only normal way. */
  private void warnIfDamaged() {
    if (versions.count() > 0 && versions.findByActiveTrue().isEmpty()) {
      log.warn("prompt versions in the table and none active; the lowest is activated");
    }
  }

  private void activateLowestIfNoneActive() {
    if (versions.findByActiveTrue().isPresent()) {
      return;
    }
    String lowest =
        store.list().stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no prompt version to activate"))
            .id();
    versions.activate(lowest);
    log.info("prompt version {} activated", lowest);
  }
}
