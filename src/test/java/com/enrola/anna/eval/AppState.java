package com.enrola.anna.eval;

import com.enrola.anna.prompt.PromptVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * What an eval run takes from the app, so it evaluates what the owner runs: the settings overrides,
 * copied before the run boots so its saves never touch the app's file, and the active prompt
 * version, read from the app database, through H2's auto-server while the app runs.
 */
final class AppState {

  private AppState() {}

  /** Copies {@code data/settings.json} into {@code runDir}, when there is one. */
  static void copySettings(Path runDir) {
    Path overrides = EvalOptions.APP_DATA.resolve("settings.json");
    try {
      Files.createDirectories(runDir);
      if (Files.exists(overrides)) {
        Files.copy(overrides, runDir.resolve("settings.json"), StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot copy " + overrides + " into " + runDir, e);
    }
  }

  /**
   * The app's active prompt version, content included; empty when the app database does not exist
   * yet or predates prompt versions, and the run keeps the repo's.
   */
  static Optional<PromptVersion> activePrompt(String appDbUrl) {
    SingleConnectionDataSource db =
        new SingleConnectionDataSource(appDbUrl + ";IFEXISTS=TRUE", true);
    try {
      return JdbcClient.create(db)
          .sql("SELECT * FROM prompt_version WHERE active = TRUE")
          .query(PromptVersion.class)
          .optional();
    } catch (DataAccessException _) {
      return Optional.empty();
    } finally {
      db.destroy();
    }
  }
}
