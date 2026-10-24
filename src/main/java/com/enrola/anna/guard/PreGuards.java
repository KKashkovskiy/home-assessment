package com.enrola.anna.guard;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.config.Settings;
import com.enrola.anna.lead.Suppression;
import com.enrola.anna.prompt.Template;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Everything decided before the main model call, in an order that is the specification and the
 * order of {@link Step}: a lone CANCEL after a booking first, because it asks to change the call,
 * not to stop the messages; a STOP keyword second, because it works with no model and overrides
 * everything; self-harm third, because the safety template goes out once whatever the state;
 * silence after a hard end or a console reset fourth, so nothing more is spent on that
 * conversation; abuse fifth, so a distressed lead who swears gets Lifeline, not a cut-off; then
 * whether the model can be called at all; then the soft-terminal classify-only path; then the turn
 * cap. Pure: built for one turn from the limits and thresholds that turn read, it reads plain
 * values and returns a {@link Ruling}, and the caller acts on it.
 */
public class PreGuards {

  /** What the turn does next; the caller switches over it exhaustively. */
  public sealed interface Outcome
      permits Proceed,
          ClassifyOnly,
          BookingChange,
          SuppressRepeat,
          Unsubscribe,
          EndAbusive,
          SafetyReply,
          FixedClose,
          HandoffNoModel,
          Silence,
          Reset {}

  /** Call the model and act on its decision. */
  public record Proceed() implements Outcome {}

  /** Soft-terminal: the model classifies, and replies only through the closing-reply rule. */
  public record ClassifyOnly() implements Outcome {}

  /** Hand off RESCHEDULE, keep the booking, send the fixed booking-change template. */
  public record BookingChange() implements Outcome {}

  /** A repeat of a request already acknowledged: no reply, flagged LOOP_SUPPRESSED. */
  public record SuppressRepeat() implements Outcome {}

  /** End UNSUBSCRIBED, suppress the phone, send the fixed confirmation. */
  public record Unsubscribe(Suppression.Reason reason) implements Outcome {}

  /** End ABUSIVE with no reply. */
  public record EndAbusive(Detector detector) implements Outcome {}

  /** Send the fixed safety template, once per conversation. */
  public record SafetyReply() implements Outcome {}

  /** End with {@code reason} and send {@code template} as the one closing reply. */
  public record FixedClose(Template template, EndReason reason) implements Outcome {}

  /** The model cannot be called: a human looks, nothing is sent, the conversation stays open. */
  public record HandoffNoModel(NoModelReason reason) implements Outcome {}

  /** The conversation ended hard with {@code endReason}: store the inbound and nothing else. */
  public record Silence(EndReason endReason) implements Outcome {}

  /**
   * The console started over with this lead, so this conversation is history: store the inbound and
   * nothing else.
   */
  public record Reset() implements Outcome {}

  /**
   * The checks in the order they run, each named after what makes it decide; the first that does
   * decides the turn.
   */
  public enum Step {
    /** A lone CANCEL while a call is booked. */
    BOOKING_CHANGE,
    /** A STOP keyword from a conversation not already unsubscribed. */
    STOP_KEYWORD,
    /** A self-harm score at its threshold, before the safety template has gone out. */
    SELF_HARM,
    /** A hard end, answered with silence. */
    TERMINAL_CHECK,
    /** A conversation the console started over. */
    RESET,
    /** A harassment or threat score at its threshold. */
    ABUSE_SCORE,
    /** A pinned prompt version that does not load. */
    PINNED_PROMPT,
    /** No model key configured. */
    MODEL_KEY,
    /** The app's or the conversation's spend cap reached. */
    SPEND_CAP,
    /** A soft end, where the model only classifies. */
    SOFT_END,
    /** The agent-turn cap reached. */
    TURN_CAP;

    /** The name in the trace: "stop-keyword". */
    public String traceName() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
  }

  /**
   * The outcome and the steps evaluated to reach it, in order: each passed the turn on but the
   * last, which decided it unless the outcome is {@link Proceed}. Steps after it did not run.
   */
  public record Ruling(Outcome outcome, List<Step> evaluated) {

    /** Whether {@code step} chose the outcome rather than passing the turn on. */
    public boolean decidedBy(Step step) {
      return !(outcome instanceof Proceed) && evaluated.getLast() == step;
    }
  }

  /** What recognised abuse: the moderation endpoint, or the model's own ABUSIVE intent. */
  public enum Detector {
    MODERATION,
    MODEL
  }

  /**
   * Why the model cannot be called: the conversation's prompt version does not load, no key, or a
   * spend cap reached.
   */
  public enum NoModelReason {
    PROMPT,
    NO_MODEL,
    SPEND_CAP_APP,
    SPEND_CAP_CONVERSATION
  }

  /**
   * The conversation before this turn: its end reason, NONE while open; whether the console reset
   * it; whether the safety template already went out; the agent turns used; whether a call is
   * booked; and the latest handoff reason.
   */
  public record Standing(
      EndReason endReason,
      boolean reset,
      boolean safetySent,
      int agentTurns,
      boolean booked,
      HandoffReason handoffReason) {}

  /**
   * Whether the main model can be called: the conversation's pinned prompt loaded, a key is
   * configured and neither spend cap is reached.
   */
  public record ModelAccess(
      boolean promptLoaded,
      boolean configured,
      boolean appCapReached,
      boolean conversationCapReached) {}

  private final Settings.Limits limits;
  private final Settings.Moderation thresholds;

  public PreGuards(Settings.Limits limits, Settings.Moderation thresholds) {
    this.limits = limits;
    this.thresholds = thresholds;
  }

  /** One turn's inputs, as {@link #decide} took them. */
  private record Inbound(
      String text, ModerationVerdict verdict, Standing standing, ModelAccess model) {}

  /**
   * The ruling for one inbound {@code text}: the steps run in order until one decides. A lone
   * CANCEL after a hard end is a STOP like any other, and one after the booking change was
   * acknowledged gets no second acknowledgement. A STOP from a conversation already unsubscribed is
   * not a new opt-out, so its confirmation is not sent twice.
   */
  public Ruling decide(
      String text, ModerationVerdict verdict, Standing standing, ModelAccess model) {
    Inbound inbound = new Inbound(text, verdict, standing, model);
    List<Step> evaluated = new ArrayList<>();
    for (Step step : Step.values()) {
      evaluated.add(step);
      Optional<Outcome> decided = check(step, inbound);
      if (decided.isPresent()) {
        return new Ruling(decided.get(), List.copyOf(evaluated));
      }
    }
    return new Ruling(new Proceed(), List.copyOf(evaluated));
  }

  private Optional<Outcome> check(Step step, Inbound inbound) {
    Standing standing = inbound.standing();
    ModelAccess model = inbound.model();
    return switch (step) {
      case BOOKING_CHANGE -> bookingChange(inbound.text(), standing);
      case STOP_KEYWORD ->
          when(
              StopKeywords.isStop(inbound.text()) && standing.endReason() != EndReason.UNSUBSCRIBED,
              new Unsubscribe(Suppression.Reason.KEYWORD));
      case SELF_HARM ->
          when(selfHarm(inbound.verdict()) && !standing.safetySent(), new SafetyReply());
      case TERMINAL_CHECK ->
          when(standing.endReason().isHardTerminal(), new Silence(standing.endReason()));
      case RESET -> when(standing.reset(), new Reset());
      case ABUSE_SCORE -> when(abusive(inbound.verdict()), new EndAbusive(Detector.MODERATION));
      case PINNED_PROMPT -> when(!model.promptLoaded(), new HandoffNoModel(NoModelReason.PROMPT));
      case MODEL_KEY -> when(!model.configured(), new HandoffNoModel(NoModelReason.NO_MODEL));
      case SPEND_CAP -> spendCap(model);
      case SOFT_END -> when(standing.endReason() != EndReason.NONE, new ClassifyOnly());
      case TURN_CAP ->
          when(
              standing.agentTurns() >= limits.maxTurns(),
              new FixedClose(Template.MAX_TURNS_CLOSE, EndReason.MAX_TURNS));
    };
  }

  private static Optional<Outcome> bookingChange(String text, Standing standing) {
    if (!StopKeywords.isLoneCancel(text)
        || !standing.booked()
        || standing.endReason().isHardTerminal()) {
      return Optional.empty();
    }
    return Optional.of(
        standing.handoffReason() == HandoffReason.RESCHEDULE
            ? new SuppressRepeat()
            : new BookingChange());
  }

  private static Optional<Outcome> spendCap(ModelAccess model) {
    if (model.appCapReached()) {
      return Optional.of(new HandoffNoModel(NoModelReason.SPEND_CAP_APP));
    }
    return when(
        model.conversationCapReached(), new HandoffNoModel(NoModelReason.SPEND_CAP_CONVERSATION));
  }

  private static Optional<Outcome> when(boolean applies, Outcome outcome) {
    return applies ? Optional.of(outcome) : Optional.empty();
  }

  private boolean selfHarm(ModerationVerdict verdict) {
    return verdict.available() && verdict.selfHarm() >= thresholds.modSelfHarm();
  }

  private boolean abusive(ModerationVerdict verdict) {
    return verdict.available()
        && (verdict.harassment() >= thresholds.modHarassment()
            || verdict.threat() >= thresholds.modThreat());
  }
}
