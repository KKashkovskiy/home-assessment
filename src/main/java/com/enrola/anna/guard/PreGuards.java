package com.enrola.anna.guard;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.config.LimitsProperties;
import com.enrola.anna.config.ModerationProperties;
import com.enrola.anna.lead.Suppression;
import com.enrola.anna.prompt.Template;
import org.springframework.stereotype.Component;

/**
 * Everything decided before the main model call, in an order that is the specification: a lone
 * CANCEL after a booking first, because it asks to change the call, not to stop the messages; a
 * STOP keyword second, because it works with no model and overrides everything; self-harm third,
 * because the safety template goes out once whatever the state; hard-terminal silence fourth, so
 * nothing more is spent on that conversation; abuse fifth, so a distressed lead who swears gets
 * Lifeline, not a cut-off; then whether the model can be called at all; then the soft-terminal
 * classify-only path; then the turn cap. Pure: it reads plain values and returns an {@link
 * Outcome}, and the caller acts on it.
 */
@Component
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
          Silence {}

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
   * The conversation before this turn: its end reason, NONE while open; whether the safety template
   * already went out; the agent turns used; whether a call is booked; and the latest handoff
   * reason.
   */
  public record Standing(
      EndReason endReason,
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

  private final LimitsProperties limits;
  private final ModerationProperties thresholds;

  public PreGuards(LimitsProperties limits, ModerationProperties thresholds) {
    this.limits = limits;
    this.thresholds = thresholds;
  }

  /**
   * The outcome for one inbound {@code text}. A lone CANCEL after a hard end is a STOP like any
   * other, and one after the booking change was acknowledged gets no second acknowledgement. A STOP
   * from a conversation already unsubscribed is not a new opt-out, so its confirmation is not sent
   * twice.
   */
  public Outcome decide(
      String text, ModerationVerdict verdict, Standing standing, ModelAccess model) {
    if (StopKeywords.isLoneCancel(text)
        && standing.booked()
        && !standing.endReason().isHardTerminal()) {
      return standing.handoffReason() == HandoffReason.RESCHEDULE
          ? new SuppressRepeat()
          : new BookingChange();
    }
    if (StopKeywords.isStop(text) && standing.endReason() != EndReason.UNSUBSCRIBED) {
      return new Unsubscribe(Suppression.Reason.KEYWORD);
    }
    if (selfHarm(verdict) && !standing.safetySent()) {
      return new SafetyReply();
    }
    if (standing.endReason().isHardTerminal()) {
      return new Silence(standing.endReason());
    }
    if (abusive(verdict)) {
      return new EndAbusive(Detector.MODERATION);
    }
    if (!model.promptLoaded()) {
      return new HandoffNoModel(NoModelReason.PROMPT);
    }
    if (!model.configured()) {
      return new HandoffNoModel(NoModelReason.NO_MODEL);
    }
    if (model.appCapReached()) {
      return new HandoffNoModel(NoModelReason.SPEND_CAP_APP);
    }
    if (model.conversationCapReached()) {
      return new HandoffNoModel(NoModelReason.SPEND_CAP_CONVERSATION);
    }
    if (standing.endReason() != EndReason.NONE) {
      return new ClassifyOnly();
    }
    if (standing.agentTurns() >= limits.maxTurns()) {
      return new FixedClose(Template.MAX_TURNS_CLOSE, EndReason.MAX_TURNS);
    }
    return new Proceed();
  }

  private boolean selfHarm(ModerationVerdict verdict) {
    return verdict.available() && verdict.selfHarm() >= thresholds.selfHarm();
  }

  private boolean abusive(ModerationVerdict verdict) {
    return verdict.available()
        && (verdict.harassment() >= thresholds.harassment()
            || verdict.threat() >= thresholds.threat());
  }
}
