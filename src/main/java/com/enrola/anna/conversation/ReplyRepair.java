package com.enrola.anna.conversation;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.guard.ReplyCheck;
import com.enrola.anna.guard.ReplyContext;
import com.enrola.anna.guard.ReplyGuards;
import com.enrola.anna.guard.Violation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The {@link ReplyGuards} around the model's replies, with one repair. A reply that fails is asked
 * for again once, with the turn's question and a note naming what failed, and with no tools when
 * the decision ended the conversation; only the rewrite's text is used, so the decision already
 * applied stands. A rewrite that fails too, or a repair call that fails, is replaced. Two calls at
 * most, by structure. Every reply checked leaves one GUARD event per {@link ReplyCheck} with that
 * check's notes, never the reply itself.
 */
@Component
class ReplyRepair {

  private final ReplyGuards guards;
  private final TurnQuestions questions;
  private final AgentCall calls;
  private final GuardOutcomes outcomes;
  private final TraceLog trace;

  ReplyRepair(
      ReplyGuards guards,
      TurnQuestions questions,
      AgentCall calls,
      GuardOutcomes outcomes,
      TraceLog trace) {
    this.guards = guards;
    this.questions = questions;
    this.calls = calls;
    this.outcomes = outcomes;
    this.trace = trace;
  }

  /**
   * What an open conversation's turn sends for the {@code decision}'s reply; a repair draws on the
   * turn's {@code rounds}.
   */
  TurnOutcome send(
      RunningTurn turn,
      Decision decision,
      TurnQuestions.Question question,
      AgentCall.ToolRounds rounds) {
    LeadIntent intent = decision.leadIntent();
    ReplyGuards.Checked first = check(turn, decision.reply(), intent, TraceOutcome.REPAIR);
    if (first.passed()) {
      return TurnOutcome.decided(first.text());
    }
    TurnQuestions.Question again =
        questions.repairOf(turn, question, ReplyGuards.note(first.violations()));
    AgentCall.Answer repair = calls.repair(turn, again, rounds);
    return rewriteOrReplacement(turn, intent, repair).withRepairCall(repair.call());
  }

  /**
   * The {@code decision}'s closing reply after an end: the same checks, each failure traced BLOCK,
   * and no repair.
   */
  ReplyGuards.Checked checkClosing(RunningTurn turn, Decision decision) {
    return check(turn, decision.reply(), decision.leadIntent(), TraceOutcome.BLOCK);
  }

  /**
   * The rewrite when it passes the checks the first decision's {@code intent} sets, marked
   * LENGTH_REPAIRED whatever the cause; else a replacement.
   */
  private TurnOutcome rewriteOrReplacement(
      RunningTurn turn, LeadIntent intent, AgentCall.Answer repair) {
    Optional<String> rewrite = rewriteOf(repair);
    if (rewrite.isEmpty()) {
      return outcomes.replyRejected(turn, intent);
    }
    ReplyGuards.Checked checked = check(turn, rewrite.get(), intent, TraceOutcome.BLOCK);
    if (!checked.passed()) {
      return outcomes.replyRejected(turn, intent);
    }
    turn.conversation().addFlag(Flag.LENGTH_REPAIRED);
    return TurnOutcome.repaired(checked.text());
  }

  private static Optional<String> rewriteOf(AgentCall.Answer repair) {
    return switch (repair) {
      case AgentCall.Answer.Decided(Decision decision, _) when decision.sendReply() ->
          Optional.of(decision.reply());
      case AgentCall.Answer.Decided _, AgentCall.Answer.Failed _ -> Optional.empty();
    };
  }

  private ReplyGuards.Checked check(
      RunningTurn turn, String reply, LeadIntent intent, TraceOutcome onFailure) {
    ReplyGuards.Checked checked = guards.validate(reply, contextFor(turn, intent));
    for (ReplyCheck check : ReplyCheck.values()) {
      List<String> notes =
          checked.violations().stream()
              .filter(violation -> violation.check() == check)
              .map(Violation::note)
              .toList();
      trace.record(
          turn,
          TraceLog.Entry.guard(
              check.traceName(),
              notes.isEmpty() ? TraceOutcome.PASS : onFailure,
              payload(check, notes, checked.text(), turn.settings().limits().maxChars())));
    }
    return checked;
  }

  /** The check's notes; the length row also carries what was measured and the limit. */
  private static Map<String, Object> payload(
      ReplyCheck check, List<String> notes, String text, int limit) {
    return check == ReplyCheck.LENGTH
        ? Map.of("chars", text.length(), "limit", limit, "notes", notes)
        : Map.of("notes", notes);
  }

  /**
   * The reply may name only times from the latest offer and the booking, this turn's tool calls
   * included. After an end, or on the turn that ends it, the reply closes the conversation.
   */
  private ReplyContext contextFor(RunningTurn turn, LeadIntent intent) {
    Conversation conversation = turn.conversation();
    return new ReplyContext(
        turn.lead(),
        intent,
        turn.prompt().canary(),
        conversation.lastOfferedLabels(),
        questions.bookingLabel(conversation),
        conversation.goalMet,
        conversation.ended,
        turn.settings().limits().maxChars());
  }
}
