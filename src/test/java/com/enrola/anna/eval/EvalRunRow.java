package com.enrola.anna.eval;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Records a finished run as one {@code eval_run} row in the app database, not the run's own, since
 * the Analytics compare pane reads it there: counts and the report path, never transcripts. It
 * connects as the app does, with no user and, through H2's auto-server, while the app runs; it
 * never creates or migrates that database, so one the app has not opened since {@code V3} gets no
 * row, and the report says why.
 */
final class EvalRunRow {

  private static final Logger log = LoggerFactory.getLogger(EvalRunRow.class);

  private EvalRunRow() {}

  /** Writes the row, or returns why it could not, for the report; the run's files are written. */
  static Optional<String> record(RunPlan plan, RunSummary summary) {
    SingleConnectionDataSource db =
        new SingleConnectionDataSource(plan.appDbUrl() + ";IFEXISTS=TRUE", true);
    try {
      insert(JdbcClient.create(db), plan, summary);
      return Optional.empty();
    } catch (DataAccessException e) {
      log.warn("eval_run row not written to {}: {}", plan.appDbUrl(), e.getMessage());
      return Optional.of(
          "No eval_run row: %s could not take it (%s). Start the app once to create or migrate it."
              .formatted(plan.appDbUrl(), e.getMostSpecificCause().getMessage()));
    } finally {
      db.destroy();
    }
  }

  private static void insert(JdbcClient db, RunPlan plan, RunSummary summary) {
    RunHeader header = plan.header();
    db.sql(
            "INSERT INTO eval_run (id, started_at, finished_at, prompt_version, model, offline,"
                + " selected, passed, failed, invalid, skipped, verdict, cost_usd, report_path)"
                + " VALUES (:id, :started, :finished, :version, :model, :offline, :selected,"
                + " :passed, :failed, :invalid, :skipped, :verdict, :cost, :report)")
        .param("id", header.runId())
        .param("started", utc(header.startedAt()))
        .param("finished", utc(Instant.now()))
        .param("version", header.promptVersion())
        .param("model", header.model())
        .param("offline", header.offline())
        .param("selected", summary.selected())
        .param("passed", summary.count(TrialStatus.PASS))
        .param("failed", summary.count(TrialStatus.FAIL) + summary.crashed())
        .param("invalid", summary.count(TrialStatus.INVALID))
        .param("skipped", summary.skipped())
        .param("verdict", summary.verdict().name())
        .param("cost", summary.cost())
        .param("report", plan.dir().resolve("report.md").toString())
        .update();
  }

  private static OffsetDateTime utc(Instant at) {
    return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
  }
}
