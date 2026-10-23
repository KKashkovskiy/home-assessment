package com.enrola.anna.web;

import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.ConversationStatus;
import com.enrola.anna.conversation.RoutingGuards;
import com.enrola.anna.conversation.TraceOutcome;
import com.enrola.anna.conversation.TurnStatus;
import com.enrola.anna.lead.Suppression;
import com.enrola.anna.scheduling.SchedulingTools;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The rows the analytics page is computed from: plain SQL over conversation, message, turn,
 * trace_event, booking and suppression, each read bounded by one cohort. Nothing is aggregated
 * ahead or cached: at the volumes this build serves each read takes milliseconds, so the page is
 * always current.
 */
@Component
class AnalyticsQueries {

  /** The objection a decision named, in the decision JSON as the turn stored it. */
  private static final String OBJECTION_TYPE = "\"objection_type\":\"([A-Z_]+)\"";

  private final JdbcClient db;

  AnalyticsQueries(JdbcClient db) {
    this.db = db;
  }

  /**
   * The conversations a filter selects, as a WHERE on the alias {@code c} and its parameters. A
   * conversation the console reset is not a start: it never counts.
   */
  record Cohort(String where, Map<String, Object> params) {

    static Cohort of(AnalyticsFilter filter, Instant since) {
      List<String> conditions = new ArrayList<>(List.of("c.status <> 'RESET'"));
      Map<String, Object> params = new HashMap<>();
      if (since != null) {
        conditions.add("c.created_at >= :since");
        params.put("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
      }
      if (filter.source() != null) {
        conditions.add("c.source = :source");
        params.put("source", filter.source().name());
      }
      if (filter.model() != null) {
        conditions.add("c.model = :model");
        params.put("model", filter.model());
      }
      if (filter.promptVersion() != null) {
        conditions.add("c.prompt_version = :promptVersion");
        params.put("promptVersion", filter.promptVersion());
      }
      return new Cohort(String.join(" AND ", conditions), Map.copyOf(params));
    }
  }

  /**
   * One conversation as the figures see it. {@code optOutReason} is the lead's suppression, null
   * without one. {@code bookedAt} and {@code repliesToBook}, the agent's replies up to and
   * including the turn that booked, are null without a booking.
   */
  record Started(
      ConversationStatus status,
      Stage stage,
      boolean goalMet,
      boolean unsubscribed,
      Suppression.Reason optOutReason,
      boolean needsHuman,
      HandoffReason handoffReason,
      Integer repliesToBook,
      boolean replied,
      Instant createdAt,
      Instant bookedAt) {}

  /** One turn; {@code model} is null for a turn that never called it. */
  record TurnRow(
      String conversationId,
      String model,
      TurnStatus status,
      BigDecimal costUsd,
      int latencyMs,
      int tokensIn,
      int tokensCached,
      int tokensOut,
      int tokensReasoning,
      Instant createdAt) {

    boolean calledModel() {
      return model != null;
    }

    /** The reply needed the repair call, or fell back to a template after it. */
    boolean retried() {
      return status == TurnStatus.REPAIRED || status == TurnStatus.FALLBACK;
    }

    boolean failed() {
      return status == TurnStatus.ERROR;
    }
  }

  /** How many GUARD rows of one name came out one way. */
  record GuardCount(String name, TraceOutcome outcome, long count) {}

  /** Conversations whose decisions named one objection type, and how many of them booked. */
  record ObjectionCount(String type, long conversations, long recovered) {}

  /** One eval run's trials that passed, out of those judged: PASS or FAIL. */
  record EvalRunCounts(long passed, long judged) {}

  /**
   * The cohort's conversations. The turn that booked is the first whose book_call answered booked;
   * without its trace row every reply counts.
   */
  List<Started> conversations(Cohort cohort) {
    Map<String, Object> params = new HashMap<>(cohort.params());
    params.put("bookCall", SchedulingTools.BOOK_CALL);
    return db.sql(
            """
            SELECT c.status, c.stage, c.goal_met, c.unsubscribed, c.needs_human,
                   c.handoff_reason, c.created_at, b.created_at AS booked_at,
                   CASE WHEN b.id IS NULL THEN NULL ELSE (
                     SELECT COUNT(*) FROM message m
                     WHERE m.conversation_id = c.id AND m.direction = 'OUT'
                       AND m.turn_no BETWEEN 1 AND COALESCE((
                         SELECT MIN(e.turn_no) FROM trace_event e
                         WHERE e.conversation_id = c.id AND e.type = 'TOOL_CALL'
                           AND e.name = :bookCall AND e.outcome = 'PASS'), m.turn_no))
                   END AS replies_to_book,
                   EXISTS (SELECT 1 FROM message m
                           WHERE m.conversation_id = c.id AND m.direction = 'IN') AS replied,
                   (SELECT MAX(s.reason) FROM suppression s
                    WHERE s.lead_id = c.lead_id) AS opt_out_reason
            FROM conversation c LEFT JOIN booking b ON b.conversation_id = c.id
            WHERE\s"""
                + cohort.where())
        .params(params)
        .query((rs, i) -> started(rs))
        .list();
  }

  List<TurnRow> turns(Cohort cohort) {
    return db.sql(
            """
            SELECT t.conversation_id, t.model, t.status, t.cost_usd, t.latency_ms, t.tokens_in,
                   t.tokens_cached, t.tokens_out, t.tokens_reasoning, t.created_at
            FROM turn t JOIN conversation c ON c.id = t.conversation_id
            WHERE\s"""
                + cohort.where())
        .params(cohort.params())
        .query((rs, i) -> turn(rs))
        .list();
  }

  List<GuardCount> guardCounts(Cohort cohort) {
    return db.sql(
            """
            SELECT e.name, e.outcome, COUNT(*) AS n
            FROM trace_event e JOIN conversation c ON c.id = e.conversation_id
            WHERE e.type = 'GUARD' AND\s"""
                + cohort.where()
                + " GROUP BY e.name, e.outcome")
        .params(cohort.params())
        .query(
            (rs, i) ->
                new GuardCount(
                    rs.getString("name"),
                    TraceOutcome.valueOf(rs.getString("outcome")),
                    rs.getLong("n")))
        .list();
  }

  /**
   * Turns on which a guard caught the model: a reply check that failed, or a field code overrode. A
   * turn counts once however many checks it failed; {@link RoutingGuards} rows never count.
   */
  long violations(Cohort cohort) {
    Map<String, Object> params = new HashMap<>(cohort.params());
    params.put("routing", RoutingGuards.NAMES);
    return db.sql(
            """
            SELECT COUNT(*) FROM (
              SELECT DISTINCT e.conversation_id, e.turn_no
              FROM trace_event e JOIN conversation c ON c.id = e.conversation_id
              WHERE e.type = 'GUARD' AND e.outcome IN ('BLOCK', 'REPAIR', 'OVERRIDE')
                AND e.name NOT IN (:routing) AND\s"""
                + cohort.where()
                + ")")
        .params(params)
        .query(Long.class)
        .single();
  }

  /**
   * Per objection type in any decision, the conversations that raised it and those of them that
   * still booked. Matches the decision JSON's text as the list's objection filter does.
   */
  // ponytail: a regex over every decision in range, fine to about 50k turns a range; an
  // objection_type column written at turn time is the upgrade
  List<ObjectionCount> objections(Cohort cohort) {
    Map<String, Object> params = new HashMap<>(cohort.params());
    params.put("objectionType", OBJECTION_TYPE);
    return db.sql(
            """
            SELECT o.objection, COUNT(DISTINCT o.conversation_id) AS conversations,
                   COUNT(DISTINCT CASE WHEN o.goal_met THEN o.conversation_id END) AS recovered
            FROM (SELECT t.conversation_id, c.goal_met,
                         REGEXP_SUBSTR(t.decision_json, :objectionType, 1, 1, NULL, 1) AS objection
                  FROM turn t JOIN conversation c ON c.id = t.conversation_id
                  WHERE\s"""
                + cohort.where()
                + ") o WHERE o.objection <> 'NONE' GROUP BY o.objection")
        .params(params)
        .query(
            (rs, i) ->
                new ObjectionCount(
                    rs.getString("objection"),
                    rs.getLong("conversations"),
                    rs.getLong("recovered")))
        .list();
  }

  /**
   * The latest keyed eval run on {@code promptVersion}: its passed trials over those judged, the
   * run's own per-trial pass rate. Skipped and INVALID trials say nothing about the prompt and are
   * left out. An offline run proves the harness, not the prompt, and a run that skipped everything
   * says nothing, so neither counts.
   */
  Optional<EvalRunCounts> latestKeyedEvalRun(String promptVersion) {
    return db.sql(
            "SELECT passed, passed + failed AS judged FROM eval_run"
                + " WHERE prompt_version = :version AND offline = FALSE AND verdict <> 'SKIPPED'"
                + " ORDER BY started_at DESC LIMIT 1")
        .param("version", promptVersion)
        .query((rs, _) -> new EvalRunCounts(rs.getLong("passed"), rs.getLong("judged")))
        .optional();
  }

  /** Every model a conversation has started on, for the filter's choices. */
  List<String> models() {
    return db.sql("SELECT DISTINCT model FROM conversation WHERE model IS NOT NULL ORDER BY model")
        .query(String.class)
        .list();
  }

  private static Started started(ResultSet rs) throws SQLException {
    String optOut = rs.getString("opt_out_reason");
    return new Started(
        ConversationStatus.valueOf(rs.getString("status")),
        Stage.valueOf(rs.getString("stage")),
        rs.getBoolean("goal_met"),
        rs.getBoolean("unsubscribed"),
        optOut == null ? null : Suppression.Reason.valueOf(optOut),
        rs.getBoolean("needs_human"),
        HandoffReason.valueOf(rs.getString("handoff_reason")),
        rs.getObject("replies_to_book", Integer.class),
        rs.getBoolean("replied"),
        instant(rs, "created_at"),
        instant(rs, "booked_at"));
  }

  private static TurnRow turn(ResultSet rs) throws SQLException {
    return new TurnRow(
        rs.getString("conversation_id"),
        rs.getString("model"),
        TurnStatus.valueOf(rs.getString("status")),
        rs.getBigDecimal("cost_usd"),
        rs.getInt("latency_ms"),
        rs.getInt("tokens_in"),
        rs.getInt("tokens_cached"),
        rs.getInt("tokens_out"),
        rs.getInt("tokens_reasoning"),
        instant(rs, "created_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }
}
