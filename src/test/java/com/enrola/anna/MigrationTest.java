package com.enrola.anna;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.conversation.ConversationService;
import com.enrola.anna.conversation.InboundTurns;
import com.enrola.anna.conversation.TurnResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * A data/ directory from before migrations has tables and no Flyway history. Boot adopts it at
 * version 0, so the first migration, the old schema.sql, adds whatever it lacks, and keeps its
 * rows. Each case boots the app on its own database, with no key and no web server; the fresh path
 * is {@link AnnaApplicationTest}.
 */
class MigrationTest {

  @TempDir Path dir;

  @Test
  void dataFromTheLastSchemaSqlIsAdoptedAndKeepsRows() throws SQLException {
    oldDatabase();

    try (ConfigurableApplicationContext app = boot()) {
      JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
      assertThat(history(jdbc))
          .containsExactly(
              "0 BASELINE TRUE",
              "1 SQL TRUE",
              "2 SQL TRUE",
              "2.1 SQL TRUE",
              "2.2 SQL TRUE",
              "3 SQL TRUE");
      assertThat(app.getBean(ConversationService.class).view("c-old").promptVersion())
          .isEqualTo("v1");
      assertThat(
              jdbc.queryForList(
                  "select id || ' ' || active || ' ' || created_by from prompt_version",
                  String.class))
          .containsExactly("v1 TRUE repo");
    }
  }

  /** The case that used to need data/ deleted: the turn reads the booking table V1 adds. */
  @Test
  void dataFromBeforeBookingsGetsWhatItLacks() throws SQLException {
    oldDatabase(
        "drop table booking",
        "alter table conversation drop column last_offered_slots_json",
        "alter table conversation drop column upstream_errors",
        "alter table turn drop column tool_calls_json");

    try (ConfigurableApplicationContext app = boot()) {
      TurnResponse turn = app.getBean(InboundTurns.class).handle("c-old", "hello", "p1");

      assertThat(turn.handoffReason()).isEqualTo(HandoffReason.ERROR);
      assertThat(
              app.getBean(JdbcTemplate.class)
                  .queryForObject(
                      "select upstream_errors from conversation where id = 'c-old'", Integer.class))
          .isZero();
    }
  }

  /**
   * The last schema.sql, which V1 is, taken back by {@code olderSchema}, then one lead and one open
   * conversation.
   */
  private void oldDatabase(String... olderSchema) throws SQLException {
    try (Connection connection = DriverManager.getConnection(url());
        Statement statement = connection.createStatement()) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/migration/V1__baseline.sql"));
      for (String change : olderSchema) {
        statement.execute(change);
      }
      insertOpenConversation(statement);
    }
  }

  private static void insertOpenConversation(Statement statement) throws SQLException {
    statement.execute(
        "insert into lead (id, given_name, phone, created_at)"
            + " values ('old-lead', 'Old', '+61400000009', current_timestamp)");
    statement.execute(
        "insert into conversation (id, lead_id, status, stage, flags_json, end_reason,"
            + " end_reason_history_json, handoff_reason, lead_zone_id, prompt_version, source,"
            + " created_at, updated_at, version) values ('c-old', 'old-lead', 'ACTIVE', 'ENGAGE',"
            + " '[]', 'NONE', '[]', 'NONE', 'Australia/Melbourne', 'v1', 'API', current_timestamp,"
            + " current_timestamp, 1)");
  }

  /** The app as {@code spring-boot:run} starts it, on this test's database. */
  private ConfigurableApplicationContext boot() {
    return new SpringApplicationBuilder(AnnaApplication.class)
        .web(WebApplicationType.NONE)
        .run("--spring.datasource.url=" + url(), "--OPENAI_API_KEY=");
  }

  private String url() {
    return "jdbc:h2:file:" + dir.resolve("anna");
  }

  private static List<String> history(JdbcTemplate jdbc) {
    return jdbc.queryForList(
        "select \"version\" || ' ' || \"type\" || ' ' || \"success\""
            + " from \"flyway_schema_history\" where \"version\" is not null"
            + " order by \"installed_rank\"",
        String.class);
  }
}
