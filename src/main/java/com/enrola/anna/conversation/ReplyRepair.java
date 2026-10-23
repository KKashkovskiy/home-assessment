package com.enrola.anna.conversation;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.config.SmsProperties;
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
  private final SmsProperties sms;

  ReplyRepair(
      ReplyGuards guards,
      TurnQuestions questions,
      AgentCall calls,
      GuardOutcomes outcomes,
      TraceLog trace,
      SmsProperties sms) {
    this.guards = guards;
    this.questions = questions;
    this.calls = calls;
    this.outcomes = outcomes;
    this.trace = trace;
    this.sms = sms;
  }

  /**
   * What an open conversation's turn sends for the model's {@code reply}; a repair draws on the
   * turn's {@code rounds}.
   */
  TurnOutcome send(
      RunningTurn turn,
      String reply,
      TurnQuestions.Question question,
      AgentCall.ToolRounds rounds) {
    ReplyGuards.Checked first = check(turn, reply, TraceOutcome.REPAIR);
    if (first.passed()) {
      return TurnOutcome.decided(first.text());
    }
    TurnQuestions.Question again =
        questions.repairOf(turn, question, ReplyGuards.note(first.violations()));
    AgentCall.Answer repair =
        turn.conversation().ended
            ? calls.classify(turn, again, rounds)
            : calls.ask(turn, again, rounds);
    return rewriteOrReplacement(turn, repair).withRepairCall(repair.call());
  }

  /** A closing reply after an end: the same checks, each failure traced BLOCK, and no repair. */
  ReplyGuards.Checked checkClosing(RunningTurn turn, String reply) {
    return check(turn, reply, TraceOutcome.BLOCK);
  }

  /** The rewrite when it passes, marked LENGTH_REPAIRED whatever the cause; else a replacement. */
  private TurnOutcome rewriteOrReplacement(RunningTurn turn, AgentCall.Answer repair) {
    Optional<String> rewrite = rewriteOf(repair);
    if (rewrite.isEmpty()) {
      return outcomes.replyRejected(turn);
    }
    ReplyGuards.Checked checked = check(turn, rewrite.get(), TraceOutcome.BLOCK);
    if (!checked.passed()) {
      return outcomes.replyRejected(turn);
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

  private ReplyGuards.Checked check(RunningTurn turn, String reply, TraceOutcome onFailure) {
    ReplyGuards.Checked checked = guards.validate(reply, contextFor(turn));
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
              payload(check, notes, checked.text())));
    }
    return checked;
  }

  /** The check's notes; the length row also carries what was measured and the limit. */
  private Map<String, Object> payload(ReplyCheck check, List<String> notes, String text) {
    return check == ReplyCheck.LENGTH
        ? Map.of("chars", text.length(), "limit", sms.maxChars(), "notes", notes)
        : Map.of("notes", notes);
  }

  /**
   * The reply may name only times from the latest offer and the booking, this turn's tool calls
   * included. After an end, or on the turn that ends it, the reply closes the conversation.
   */
  private ReplyContext contextFor(RunningTurn turn) {
    Conversation conversation = turn.conversation();
    return new ReplyContext(
        turn.lead(),
        turn.prompt().canary(),
        conversation.lastOfferedLabels(),
        questions.bookingLabel(conversation),
        conversation.goalMet,
        conversation.ended,
        sms.maxChars());
  }
}
