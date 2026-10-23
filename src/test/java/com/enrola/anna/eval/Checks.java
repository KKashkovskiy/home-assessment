package com.enrola.anna.eval;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.conversation.TraceEvent;
import com.enrola.anna.conversation.TraceEventType;
import com.enrola.anna.conversation.TurnStatus;
import com.enrola.anna.eval.Scenario.Expect;
import com.enrola.anna.guard.ReplyCheck;
import com.enrola.anna.guard.ReplyContext;
import com.enrola.anna.guard.ReplyGuards;
import com.enrola.anna.guard.Violation;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The deterministic checks on one turn, every one about that turn. {@link #forTurn} turns a
 * scenario's {@code expect} block into one EXPECTATION check per field it sets, each with the value
 * seen. {@link #suiteWide} makes the HARD checks that hold on every trial unasked: the production
 * {@link ReplyGuards} on every reply, so a check here cannot drift from the guard it mirrors, and
 * the turn's status.
 */
final class Checks {

  /**
   * The reply guards every reply passes whatever wrote it. The question, exclamation and format
   * rules bind only what the model writes, so the fixed templates are not held to them here.
   */
  private static final Set<ReplyCheck> GUARDED =
      EnumSet.of(
          ReplyCheck.LENGTH,
          ReplyCheck.CANARY,
          ReplyCheck.URL,
          ReplyCheck.PII,
          ReplyCheck.DENY,
          ReplyCheck.TIME_MENTION,
          ReplyCheck.MAX_SLOTS);

  /** A turn that ends so failed: the model or the reply guards gave out, unless expected. */
  private static final Set<TurnStatus> FAILED = EnumSet.of(TurnStatus.FALLBACK, TurnStatus.ERROR);

  private static final ReplyGuards GUARDS = new ReplyGuards();

  private Checks() {}

  /** The checks {@code expect} asks for, against what the turn left in {@code rows}. */
  static List<Check> forTurn(Expect expect, TurnRows rows) {
    return Stream.of(
            decisionChecks(expect, rows),
            stateChecks(expect, rows.conversation()),
            replyChecks(expect, rows.reply()),
            callChecks(expect, rows))
        .flatMap(Function.identity())
        .map(check -> check.at(rows.turnNo()))
        .toList();
  }

  /**
   * The checks that hold whatever the scenario says: the turn did not end FALLBACK or ERROR unless
   * {@code expect} names that status, at most one closing reply after an end, and the reply guards
   * and at most one question on every reply.
   */
  static List<Check> suiteWide(Expect expect, TurnRows rows) {
    Stream<Check> turn =
        Stream.concat(
            expect.turnStatus() == null ? turnStatus(rows) : Stream.empty(),
            replyAfterEnd(rows.conversation()));
    Stream<Check> replies =
        rows.outbound().stream()
            .flatMap(message -> replyGuards(message.text(), rows.replyContext()));
    return Stream.concat(turn, replies).map(check -> check.at(rows.turnNo())).toList();
  }

  /**
   * The decision's own fields. A turn a guard answered before the model has no decision, and the
   * check names the scenario as the fault: it asked about a classification that never happened.
   */
  private static Stream<Check> decisionChecks(Expect expect, TurnRows rows) {
    Decision decision = rows.decision();
    if (decision == null) {
      String missing =
          rows.modelCalled()
              ? "no decision applied"
              : "scenario fault: a guard answered before the model";
      return Stream.of(
              missingDecision("lead_intent", expect.leadIntent(), missing),
              missingDecision("stage", expect.stage(), missing))
          .flatMap(Function.identity());
    }
    return Stream.of(
            equal("lead_intent", expect.leadIntent(), decision.leadIntent()),
            equal("stage", expect.stage(), decision.stage()))
        .flatMap(Function.identity());
  }

  private static Stream<Check> missingDecision(String name, Object expected, String missing) {
    return expected == null
        ? Stream.empty()
        : Stream.of(Check.expectation(name, false, missing + ", expected=" + expected));
  }

  /** Code owns these, so they are read from the conversation, not from the decision. */
  private static Stream<Check> stateChecks(Expect expect, Conversation conversation) {
    Stream<Check> flags =
        expect.flags() == null
            ? Stream.empty()
            : Stream.of(
                Check.expectation(
                    "flags",
                    conversation.flags().containsAll(expect.flags()),
                    "flags=" + conversation.flags()));
    return Stream.of(
            flags,
            equal("end_reason", expect.endReason(), conversation.endReason),
            equal("handoff_reason", expect.handoffReason(), conversation.handoffReason),
            equal("goal_met", expect.goalMet(), conversation.goalMet),
            equal("unsubscribed", expect.unsubscribed(), conversation.unsubscribed),
            equal("conversation_ended", expect.conversationEnded(), conversation.ended),
            equal("needs_human", expect.needsHuman(), conversation.needsHuman))
        .flatMap(Function.identity());
  }

  private static Stream<Check> replyChecks(Expect expect, String reply) {
    Stream<Check> matches =
        listed(expect.replyMatches())
            .map(
                regex ->
                    Check.expectation("reply_matches " + regex, finds(regex, reply), quote(reply)));
    Stream<Check> notMatches =
        listed(expect.replyNotMatches())
            .map(
                regex ->
                    Check.expectation(
                        "reply_not_matches " + regex, !finds(regex, reply), quote(reply)));
    return Stream.of(
            equal("reply_sent", expect.replySent(), reply != null),
            matches,
            notMatches,
            equal("ends_with_question", expect.endsWithQuestion(), endsWithQuestion(reply)))
        .flatMap(Function.identity());
  }

  /**
   * Whether the model was called, the exact set of tools, order ignored ({@code []} is none), and
   * the turn's status when the scenario names one.
   */
  private static Stream<Check> callChecks(Expect expect, TurnRows rows) {
    Stream<Check> tools =
        expect.toolCalled() == null
            ? Stream.empty()
            : Stream.of(toolCheck(Set.copyOf(expect.toolCalled()), rows));
    return Stream.of(
            equal("model_called", expect.modelCalled(), rows.modelCalled()),
            tools,
            equal("turn_status", expect.turnStatus(), rows.status()))
        .flatMap(Function.identity());
  }

  private static Check toolCheck(Set<String> expected, TurnRows rows) {
    Set<String> called =
        rows.eventsOf(TraceEventType.TOOL_CALL).stream()
            .map(TraceEvent::name)
            .collect(Collectors.toSet());
    return Check.expectation("tool_called", called.equals(expected), "called=" + called);
  }

  /** FALLBACK or ERROR, with the turn's error events as evidence. */
  private static Stream<Check> turnStatus(TurnRows rows) {
    String errors =
        rows.eventsOf(TraceEventType.ERROR).stream()
            .map(error -> error.name() + " " + error.payloadJson())
            .collect(Collectors.joining("; "));
    return Stream.of(
        Check.hard(
            "turn_status",
            !FAILED.contains(rows.status()),
            "status=" + rows.status() + (errors.isEmpty() ? "" : " " + errors)));
  }

  /** Two or more closing replies after an end; the product allows one. */
  private static Stream<Check> replyAfterEnd(Conversation conversation) {
    return Stream.of(
        Check.hard(
            "reply_after_end",
            !conversation.ended || conversation.postTerminalReplies <= 1,
            "closing replies=" + conversation.postTerminalReplies));
  }

  /** One check per guarded reply rule, failing with the guard's notes, and the question count. */
  private static Stream<Check> replyGuards(String reply, ReplyContext context) {
    List<Violation> violations = GUARDS.validate(reply, context).violations();
    long questions = reply.chars().filter(c -> c == '?').count();
    return Stream.concat(
        GUARDED.stream().map(rule -> guardCheck(rule, violations, reply)),
        Stream.of(Check.hard("one_question_max", questions <= 1, "questions=" + questions)));
  }

  /** Named as the guard is, in the report's snake_case: {@code time_mention}. */
  private static Check guardCheck(ReplyCheck rule, List<Violation> violations, String reply) {
    String notes =
        violations.stream()
            .filter(violation -> violation.check() == rule)
            .map(Violation::note)
            .collect(Collectors.joining("; "));
    return Check.hard(
        rule.name().toLowerCase(Locale.ROOT),
        notes.isEmpty(),
        notes.isEmpty() ? quote(reply) : notes + ": " + quote(reply));
  }

  /** A check when {@code expected} is set: equal or not, with both values as evidence. */
  private static <T> Stream<Check> equal(String name, T expected, T seen) {
    if (expected == null) {
      return Stream.empty();
    }
    return Stream.of(
        Check.expectation(name, expected.equals(seen), "expected=" + expected + " seen=" + seen));
  }

  private static Stream<String> listed(List<String> regexes) {
    return regexes == null ? Stream.empty() : regexes.stream();
  }

  private static boolean finds(String regex, String reply) {
    return reply != null && Pattern.compile(regex).matcher(reply).find();
  }

  private static boolean endsWithQuestion(String reply) {
    return reply != null && reply.strip().endsWith("?");
  }

  /** The reply quoted and cut to 120 characters, for evidence. */
  static String quote(String reply) {
    if (reply == null) {
      return "<no reply>";
    }
    String cut = reply.length() > 120 ? reply.substring(0, 117) + "..." : reply;
    return '"' + cut + '"';
  }
}
