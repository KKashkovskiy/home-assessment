package com.enrola.anna;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.LeadRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the full context and proves the database settings: an H2 file in a temp directory, not
 * {@code ./data}, migrated from empty, and instants stored without loss in a JVM zone with daylight
 * saving. A blank {@code OPENAI_API_KEY} keeps an exported key from reaching OpenAI from a test,
 * here and in every Spring test.
 */
@SpringBootTest(properties = "OPENAI_API_KEY=")
class AnnaApplicationTest {

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void dataInTempDir(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("anna"));
    registry.add("anna.data-dir", tempDir::toString);
  }

  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired LeadRepository leads;

  @Test
  void databaseFileLivesInTempDirectory() throws IOException {
    String databasePath = jdbcTemplate.queryForObject("select database_path()", String.class);

    // H2 reports the canonical path; on macOS the temp dir sits behind the /var symlink.
    assertThat(Path.of(databasePath)).isEqualTo(tempDir.toRealPath().resolve("anna"));
  }

  @Test
  void freshDatabaseRunsEveryMigration() {
    assertThat(
            jdbcTemplate.queryForList(
                "select \"version\" || ' ' || \"type\" || ' ' || \"success\""
                    + " from \"flyway_schema_history\" where \"version\" is not null"
                    + " order by \"installed_rank\"",
                String.class))
        .containsExactly(
            "1 SQL TRUE",
            "2 SQL TRUE",
            "2.1 SQL TRUE",
            "2.2 SQL TRUE",
            "3 SQL TRUE",
            "4 SQL TRUE",
            "5 SQL TRUE");
    assertThat(jdbcTemplate.queryForObject("select count(*) from prompt_version", Integer.class))
        .isEqualTo(1);
  }

  /** 02:30 on 5 April 2026 happens twice in Sydney; this is the second one, AEST. */
  @Test
  void instantInTheRepeatedDstHourReadsBackUnchanged() {
    Instant repeatedHour = Instant.parse("2026-04-04T16:30:00Z");
    leads.save(
        new Lead("dst", "Dee", "+61400000099", null, "NSW", null, null, null, null, repeatedHour));

    assertThat(leads.findById("dst").orElseThrow().createdAt()).isEqualTo(repeatedHour);
  }
}
