package com.enrola.anna;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the full context and proves it talks to an H2 file in a temp directory, not {@code ./data}.
 */
@SpringBootTest
class AnnaApplicationTest {

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void databaseInTempDir(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("anna"));
  }

  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void databaseFileLivesInTempDirectory() throws IOException {
    String databasePath = jdbcTemplate.queryForObject("select database_path()", String.class);

    // H2 reports the canonical path; on macOS the temp dir sits behind the /var symlink.
    assertThat(Path.of(databasePath)).isEqualTo(tempDir.toRealPath().resolve("anna"));
  }
}
