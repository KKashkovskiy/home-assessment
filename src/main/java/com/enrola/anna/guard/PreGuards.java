package com.enrola.anna.guard;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.config.Settings;
import com.enrola.anna.lead.Suppression;
import com.enrola.anna.prompt.Template;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Everything decided before the main model call. The order below is the specification, and it is
 * the order of {@link Step}:
 *
 * <ol>
 *   <li>A lone CANCEL after a booking, because it asks to change the call, not to stop the
 *       messages.
 *   <li>A STOP keyword, because it works with no model and overrides everything.
 *   <li>Self-harm, because the safety template goes out once whatever the state. It is read from
 *       moderation's score, or from the {@link SelfHarmPhrases} when moderation did not answer.
 *       After a hard end, where no model turn follows to classify the message, it is read from the
 *       model when neither found it.
 *   <li>Silence after a hard end or a console reset, so that conversation gets no model turn.
 *   <li>Abuse, so a distressed lead who swears gets Lifeline, not a cut-off.
 *   <li>Whether the model can be called at all.
 *   <li>The soft-terminal classify-only path.
 *   <li>The turn cap.
 * </ol>
 *
 * <p>The class is pure. It is built for one turn from the limits and thresholds that turn read,
 * reads plain values and returns a {@link Ruling}; the caller acts on it.
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
          AskModelAboutSelfHarm,
          SelfHarmUnscreened,
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
  public record SafetyReply(Detector detector) implements Outcome {}

  /**
   * After a hard end, moderation did not answer and no self-harm phrase matched: the model
   * classifies the message, with no tools and no reply of its own. SELF_HARM sends the safety
   * template; anything else, or a failed call, keeps the silence.
   */
  public record AskModelAboutSelfHarm() implements Outcome {}

  /**
   * As {@link AskModelAboutSelfHarm}, but the model cannot be called either, for {@code reason}:
   * silence, as for {@link Silence}, with the message unscreened.
   */
  public record SelfHarmUnscreened(EndReason endReason, NoModelReason reason) implements Outcome {}

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
    /**
     * Before the safety template has gone out: a self-harm score at its threshold, a self-harm
     * phrase when moderation did not answer, or, after a hard end, a message to ask the model
     * about.
     */
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

  /**
   * What recognised abuse or self-harm: the moderation endpoint, the model's own intent, or, for
   * self-harm when moderation did not answer, the {@link SelfHarmPhrases} or the model asked after
   * a hard end.
   */
  public enum Detector {
    MODERATION,
    MODEL,
    SELF_HARM_PHRASES,
    MODEL_CLASSIFY
  }

  /**
   * Why the model cannot be called: the conversation's prompt version does not load, no key, or a
   * spend cap reached; in the order the steps check them.
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
   * booked; the latest handoff reason; and the lead's previous message, empty before the first.
   */
  public record Standing(
      EndReason endReason,
      boolean reset,
      boolean safetySent,
      int agentTurns,
      boolean booked,
      HandoffReason handoffReason,
      String previousInbound) {}

  /**
   * Whether the main model can be called: the conversation's pinned prompt loaded, a key is
   * configured and neither spend cap is reached.
   */
  public record ModelAccess(
      boolean promptLoaded,
      boolean configured,
      boolean appCapReached,
      boolean conversationCapReached) {

    /** Whether {@code reason} keeps the model out. */
    boolean blocks(NoModelReason reason) {
      return switch (reason) {
        case PROMPT -> !promptLoaded;
        case NO_MODEL -> !configured;
        case SPEND_CAP_APP -> appCapReached;
        case SPEND_CAP_CONVERSATION -> conversationCapReached;
      };
    }

    /** The first reason that keeps the model out, in the steps' order; empty when none does. */
    Optional<NoModelReason> blocked() {
      return Arrays.stream(NoModelReason.values()).filter(this::blocks).findFirst();
    }
  }

  private final Settings.Limits limits;
  private final Settings.Moderation thresholds;
  private final SelfHarmPhrases selfHarmPhrases;

  public PreGuards(
      Settings.Limits limits, Settings.Moderation thresholds, SelfHarmPhrases selfHarmPhrases) {
    this.limits = limits;
    this.thresholds = thresholds;
    this.selfHarmPhrases = selfHarmPhrases;
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
      case SELF_HARM -> standing.safetySent() ? Optional.empty() : selfHarm(inbound);
      case TERMINAL_CHECK ->
          when(standing.endReason().isHardTerminal(), new Silence(standing.endReason()));
      case RESET -> when(standing.reset(), new Reset());
      case ABUSE_SCORE -> when(abusive(inbound.verdict()), new EndAbusive(Detector.MODERATION));
      case PINNED_PROMPT -> noModel(model, NoModelReason.PROMPT);
      case MODEL_KEY -> noModel(model, NoModelReason.NO_MODEL);
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
    return noModel(model, NoModelReason.SPEND_CAP_APP)
        .or(() -> noModel(model, NoModelReason.SPEND_CAP_CONVERSATION));
  }

  private static Optional<Outcome> noModel(ModelAccess model, NoModelReason reason) {
    return when(model.blocks(reason), new HandoffNoModel(reason));
  }

  private static Optional<Outcome> when(boolean applies, Outcome outcome) {
    return applies ? Optional.of(outcome) : Optional.empty();
  }

  /**
   * An answering endpoint's verdict stands; only when it did not answer do the phrases decide, and
   * after them, on a hard end, the model.
   */
  private Optional<Outcome> selfHarm(Inbound inbound) {
    ModerationVerdict verdict = inbound.verdict();
    if (verdict.available()) {
      return when(
          verdict.selfHarm() >= thresholds.modSelfHarm(), new SafetyReply(Detector.MODERATION));
    }
    if (selfHarmPhrases.matches(inbound.text())) {
      return Optional.of(new SafetyReply(Detector.SELF_HARM_PHRASES));
    }
    return unscreenedAfterHardEnd(inbound);
  }

  /**
   * On an open or soft-ended conversation the model's turn classifies the message anyway. A STOP is
   * never screened, so its unavailable verdict is no reason to ask; nor is a repeat of the previous
   * message, so a lead who sends the same text again and again during an outage costs one call.
   */
  private static Optional<Outcome> unscreenedAfterHardEnd(Inbound inbound) {
    Standing standing = inbound.standing();
    EndReason endReason = standing.endReason();
    if (!endReason.isHardTerminal()
        || StopKeywords.isStop(inbound.text())
        || StopKeywords.repeats(inbound.text(), standing.previousInbound())) {
      return Optional.empty();
    }
    return Optional.of(
        inbound
            .model()
            .blocked()
            .<Outcome>map(reason -> new SelfHarmUnscreened(endReason, reason))
            .orElseGet(AskModelAboutSelfHarm::new));
  }

  private boolean abusive(ModerationVerdict verdict) {
    return verdict.available()
        && (verdict.harassment() >= thresholds.modHarassment()
            || verdict.threat() >= thresholds.modThreat());
  }
}
