package com.enrola.anna.conversation;

import com.enrola.anna.agent.AgentService;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.config.SmsProperties;
import com.enrola.anna.guard.ModerationClient;
import com.enrola.anna.guard.ModerationVerdict;
import com.enrola.anna.guard.PreGuards;
import com.enrola.anna.guard.StopKeywords;
import com.enrola.anna.guard.Suppressions;
import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.LeadRepository;
import com.enrola.anna.prompt.PromptBundle;
import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptUnavailable;
import com.enrola.anna.scheduling.BookingRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The steps of one claimed turn, in order: the prompt version the conversation pinned is loaded, a
 * phone that opted out leaves the conversation unsubscribed, moderation screens the inbound, the
 * {@link PreGuards} pick one outcome, the outcome runs, and a STATE_DIFF event records what the
 * turn changed. Moderation and the pre-guards write a GUARD event every turn, pass or not. A STOP
 * is never screened, so it cannot wait on moderation. Without a key there is no moderation
 * endpoint, so every verdict is unavailable and the trace shows the gap.
 */
@Component
class TurnPipeline {

  private final LeadRepository leads;
  private final BookingRepository bookings;
  private final PromptStore prompts;
  private final Suppressions suppressions;
  private final ModerationClient moderation;
  private final PreGuards preGuards;
  private final AgentService agent;
  private final SpendCap spend;
  private final AgentTurn agentTurn;
  private final GuardOutcomes outcomes;
  private final TraceLog trace;
  private final SmsProperties sms;

  TurnPipeline(
      LeadRepository leads,
      BookingRepository bookings,
      PromptStore prompts,
      Suppressions suppressions,
      ObjectProvider<ModerationClient> moderation,
      PreGuards preGuards,
      AgentService agent,
      SpendCap spend,
      AgentTurn agentTurn,
      GuardOutcomes outcomes,
      TraceLog trace,
      SmsProperties sms) {
    this.leads = leads;
    this.bookings = bookings;
    this.prompts = prompts;
    this.suppressions = suppressions;
    this.moderation = moderation.getIfAvailable(() -> ModerationClient.NONE);
    this.preGuards = preGuards;
    this.agent = agent;
    this.spend = spend;
    this.agentTurn = agentTurn;
    this.outcomes = outcomes;
    this.trace = trace;
    this.sms = sms;
  }

  /**
   * Runs {@code inbound} on {@code conversation}, changing it in memory; the caller stores both.
   * Text over {@code anna.sms.max-inbound-chars} flags the conversation INPUT_TRUNCATED.
   */
  TurnOutcome run(Conversation conversation, Message inbound, Instant now) {
    StateSnapshot before = StateSnapshot.of(conversation);
    RunningTurn turn =
        new RunningTurn(
            conversation,
            leadOf(conversation),
            inbound,
            now,
            bookings.findByConversationId(conversation.id).isPresent(),
            promptFor(conversation, inbound.turnNo()));
    if (inbound.charCount() > sms.maxInboundChars()) {
      conversation.addFlag(Flag.INPUT_TRUNCATED);
    }
    meetGoalOfSavedBooking(turn);
    followPhoneOptOut(turn);
    TurnOutcome outcome = route(turn, decide(turn, screen(turn)));
    countSent(conversation, outcome, before);
    anchorHealthInfo(turn);
    trace.record(
        turn,
        new TraceLog.Entry(
            TraceEventType.STATE_DIFF,
            "state",
            TraceOutcome.INFO,
            before.changesTo(StateSnapshot.of(conversation))));
    return outcome;
  }

  /**
   * The version the conversation started on, never the active one. When that version is gone or no
   * longer loads, the active one stands in for its fixed templates only, so STOP, the safety reply
   * and a booking change still work; the pre-guards keep the model out and the trace says why.
   */
  private PromptBundle promptFor(Conversation conversation, int turnNo) {
    try {
      return prompts.bundle(conversation.promptVersion);
    } catch (PromptUnavailable e) {
      String active = prompts.activeId();
      trace.record(
          conversation.id,
          turnNo,
          new TraceLog.Entry(
              TraceEventType.ERROR,
              "prompt-load",
              TraceOutcome.BLOCK,
              Map.of(
                  "code",
                  "PROMPT",
                  "version",
                  conversation.promptVersion,
                  "detail",
                  e.getMessage(),
                  "templates_from",
                  active)));
      return prompts.bundle(active);
    }
  }

  /**
   * The booking row commits before its turn does, so a turn that died in between leaves a booking
   * the conversation does not show; it is the truth, and the goal is met before anything else runs.
   */
  private static void meetGoalOfSavedBooking(RunningTurn turn) {
    if (turn.bookedAtStart() && !turn.conversation().goalMet) {
      StateRules.booked(turn.conversation(), turn.now());
    }
  }

  /**
   * A phone that opted out, on this conversation or another, leaves this one unsubscribed before
   * any check, so the checks see it as such.
   */
  private void followPhoneOptOut(RunningTurn turn) {
    if (suppressions.optedOut(turn.lead().phone())) {
      StateRules.end(turn.conversation(), EndReason.UNSUBSCRIBED, turn.now());
    }
  }

  /**
   * Every inbound but a STOP, whatever the conversation's state: self-harm must be seen after an
   * end too. A STOP needs no verdict, since it wins whatever moderation would say.
   */
  private ModerationVerdict screen(RunningTurn turn) {
    String text = turn.inbound().text();
    if (StopKeywords.isStop(text)) {
      guard(turn, "moderation", TraceOutcome.INFO, Map.of("skipped", "STOP"));
      return ModerationVerdict.UNAVAILABLE;
    }
    ModerationVerdict verdict = moderation.screen(text);
    guard(turn, "moderation", verdict.available() ? TraceOutcome.PASS : TraceOutcome.INFO, verdict);
    return verdict;
  }

  private PreGuards.Outcome decide(RunningTurn turn, ModerationVerdict verdict) {
    Conversation conversation = turn.conversation();
    PreGuards.Outcome outcome =
        preGuards.decide(
            turn.inbound().text(),
            verdict,
            new PreGuards.Standing(
                conversation.endReason,
                safetySent(conversation),
                conversation.agentTurns,
                turn.bookedAtStart(),
                conversation.handoffReason),
            new PreGuards.ModelAccess(
                turn.onPinnedPrompt(),
                agent.available(),
                spend.appCapReached(),
                spend.conversationCapReached(conversation.id)));
    guard(
        turn,
        "pre-guards",
        verdictOf(outcome),
        Map.of("outcome", outcome.getClass().getSimpleName(), "detail", outcome));
    return outcome;
  }

  /**
   * A model call goes ahead on PASS; an opt-out or a booking change is honoured, not a check that
   * failed; every other outcome stops the model.
   */
  private static TraceOutcome verdictOf(PreGuards.Outcome outcome) {
    return switch (outcome) {
      case PreGuards.Proceed _, PreGuards.ClassifyOnly _ -> TraceOutcome.PASS;
      case PreGuards.BookingChange _, PreGuards.Unsubscribe _ -> TraceOutcome.INFO;
      case PreGuards.SuppressRepeat _,
          PreGuards.EndAbusive _,
          PreGuards.SafetyReply _,
          PreGuards.FixedClose _,
          PreGuards.HandoffNoModel _,
          PreGuards.Silence _ ->
          TraceOutcome.BLOCK;
    };
  }

  private TurnOutcome route(RunningTurn turn, PreGuards.Outcome outcome) {
    return switch (outcome) {
      case PreGuards.Proceed _ -> agentTurn.run(turn);
      case PreGuards.ClassifyOnly _ -> agentTurn.classifyOnly(turn);
      case PreGuards.BookingChange _ -> outcomes.bookingChange(turn);
      case PreGuards.SuppressRepeat _ -> outcomes.suppressRepeat(turn);
      case PreGuards.Unsubscribe unsubscribe -> outcomes.unsubscribe(turn, unsubscribe.reason());
      case PreGuards.EndAbusive abusive -> outcomes.endAbusive(turn, abusive.detector());
      case PreGuards.SafetyReply _ -> outcomes.safety(turn);
      case PreGuards.FixedClose close ->
          outcomes.fixedClose(turn, close.template(), close.reason());
      case PreGuards.HandoffNoModel handoff -> outcomes.handoffWithoutModel(turn, handoff.reason());
      case PreGuards.Silence silence -> TurnOutcome.silent("ended " + silence.endReason());
    };
  }

  /**
   * A sent message is an agent turn, and on or after the turn that ended the conversation a
   * post-terminal reply too, so a fixed close or the model's sign-off is the one closing reply. The
   * reply on the turn that booked confirms the goal and closes nothing, so one closing reply may
   * still follow it.
   */
  private static void countSent(
      Conversation conversation, TurnOutcome outcome, StateSnapshot before) {
    if (outcome.reply() == null) {
      return;
    }
    conversation.agentTurns++;
    boolean confirmsBooking = conversation.goalMet && !before.goalMet();
    if (conversation.ended && !confirmsBooking) {
      conversation.postTerminalReplies++;
    }
  }

  /** The first turn to carry health information hides bodies from itself on, for good. */
  private static void anchorHealthInfo(RunningTurn turn) {
    Conversation conversation = turn.conversation();
    if (conversation.healthInfoTurn == null && conversation.flags().contains(Flag.HEALTH_INFO)) {
      conversation.healthInfoTurn = turn.turnNo();
    }
  }

  private static boolean safetySent(Conversation conversation) {
    return conversation.endReasonHistory().stream()
        .anyMatch(entry -> entry.reason() == EndReason.SAFETY);
  }

  private void guard(RunningTurn turn, String name, TraceOutcome outcome, Object payload) {
    trace.record(turn, TraceLog.Entry.guard(name, outcome, payload));
  }

  private Lead leadOf(Conversation conversation) {
    return leads
        .findById(conversation.leadId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "lead " + conversation.leadId + " missing for " + conversation.id));
  }
}
