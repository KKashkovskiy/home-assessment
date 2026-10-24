package com.enrola.anna.prompt;

import com.enrola.anna.AppClock;
import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsDefaults;
import com.enrola.anna.config.SettingsStore;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills the version table from the repo's {@code prompts/vN/} directories at boot. It runs as the
 * bean is built, after the migrations and before the server accepts a request, so no request finds
 * the table empty or the active version unloadable. It runs after {@link SettingsStore} has loaded,
 * so a fixed clock in the overrides already stamps the seeded rows; the store checks those
 * overrides against the versions once every bean, this one included, is built.
 */
@Component
@DependsOn("settingsStore")
class PromptSeed {

  private static final Logger log = LoggerFactory.getLogger(PromptSeed.class);

  private final PromptVersionRepository versions;
  private final PromptLoader loader;
  private final PromptStore store;
  private final SettingsDefaults defaults;
  private final AppClock clock;
  private final TransactionTemplate tx;

  PromptSeed(
      PromptVersionRepository versions,
      PromptLoader loader,
      PromptStore store,
      SettingsDefaults defaults,
      AppClock clock,
      TransactionTemplate tx) {
    this.versions = versions;
    this.loader = loader;
    this.store = store;
    this.defaults = defaults;
    this.clock = clock;
    this.tx = tx;
  }

  /**
   * In one transaction: inserts every repo version that has no row, activates the newest repo
   * version when none is active, and loads the active one, all with the yml's persona. A repo
   * version with any finding fails the boot, since the repo and the yml are under the owner's
   * control, and so does an active version the yml's persona cannot load. An id the table already
   * has is skipped, with a warning when the row was a save: the repo and the table then disagree.
   */
  @PostConstruct
  void seed() {
    Settings.Persona yml = defaults.settings().persona();
    tx.executeWithoutResult(
        _ -> {
          warnIfDamaged();
          List<String> repoIds = loader.availableVersions();
          repoIds.forEach(id -> seedIfAbsent(id, yml));
          activateNewestIfNoneActive(repoIds);
          store.bundle(store.activeId(), yml);
        });
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
      log.warn(
          "prompt versions in the table and none active; the newest repo version is activated");
    }
  }

  /**
   * A fresh database runs the newest version the repo ships. A save is never activated by the seed,
   * since only a person decides a save is ready, not even one that holds a repo directory's id.
   */
  private void activateNewestIfNoneActive(List<String> repoIds) {
    if (versions.findByActiveTrue().isPresent()) {
      return;
    }
    String newest =
        repoIds.reversed().stream()
            .filter(this::seededFromRepo)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no repo prompt version to activate"));
    versions.activate(newest);
    log.info("prompt version {} activated", newest);
  }

  private boolean seededFromRepo(String id) {
    return versions
        .findById(id)
        .map(version -> PromptVersion.REPO.equals(version.createdBy()))
        .orElse(false);
  }
}
