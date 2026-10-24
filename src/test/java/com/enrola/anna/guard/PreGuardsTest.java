package com.enrola.anna.guard;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.guard.PreGuards.Detector;
import com.enrola.anna.guard.PreGuards.ModelAccess;
import com.enrola.anna.guard.PreGuards.NoModelReason;
import com.enrola.anna.guard.PreGuards.Outcome;
import com.enrola.anna.guard.PreGuards.Standing;
import com.enrola.anna.guard.PreGuards.Step;
import com.enrola.anna.lead.Suppression;
import com.enrola.anna.prompt.Template;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** The pre-guard order with the shipped limits 15/3/1 and thresholds 0.5/0.5/0.3. */
class PreGuardsTest {

  static final PreGuards GUARDS =
      new PreGuards(SettingsFixture.DEFAULTS.limits(), SettingsFixture.DEFAULTS.moderation());
  static final Standing OPEN =
      new Standing(EndReason.NONE, false, false, 0, false, HandoffReason.NONE);
  static final Standing BOOKED =
      new Standing(EndReason.BOOKED, false, false, 4, true, HandoffReason.NONE);
  static final Standing RESET =
      new Standing(EndReason.NONE, true, false, 1, false, HandoffReason.NONE);
  static final ModelAccess MODEL = new ModelAccess(true, true, false, false);
  static final ModerationVerdict CLEAN = ScriptedModerationClient.CLEAN;
  static final ModerationVerdict SELF_HARM = scores(0, 0, 0.9);
  static final ModerationVerdict HARASSMENT = scores(0.9, 0, 0);

  /**
   * Each row matches two adjacent checks; the earlier one decides, last of the steps that ran, and
   * none after it runs.
   */
  static Stream<Arguments> precedence() {
    return Stream.of(
        Arguments.of(
            "a lone CANCEL after a booking over STOP",
            "Cancel.",
            CLEAN,
            BOOKED,
            MODEL,
            Step.BOOKING_CHANGE,
            new PreGuards.BookingChange()),
        Arguments.of(
            "STOP over self-harm",
            "STOP",
            SELF_HARM,
            OPEN,
            MODEL,
            Step.STOP_KEYWORD,
            new PreGuards.Unsubscribe(Suppression.Reason.KEYWORD)),
        Arguments.of(
            "self-harm over a hard end",
            "...",
            SELF_HARM,
            new Standing(EndReason.ABUSIVE, false, false, 1, false, HandoffReason.NONE),
            MODEL,
            Step.SELF_HARM,
            new PreGuards.SafetyReply()),
        Arguments.of(
            "STOP over a reset",
            "STOP",
            CLEAN,
            RESET,
            MODEL,
            Step.STOP_KEYWORD,
            new PreGuards.Unsubscribe(Suppression.Reason.KEYWORD)),
        Arguments.of(
            "self-harm over a reset",
            "...",
            SELF_HARM,
            RESET,
            MODEL,
            Step.SELF_HARM,
            new PreGuards.SafetyReply()),
        Arguments.of(
            "a reset over abuse",
            "...",
            HARASSMENT,
            RESET,
            MODEL,
            Step.RESET,
            new PreGuards.Reset()),
        Arguments.of(
            "a hard end over abuse",
            "...",
            HARASSMENT,
            new Standing(EndReason.ABUSIVE, false, false, 1, false, HandoffReason.NONE),
            MODEL,
            Step.TERMINAL_CHECK,
            new PreGuards.Silence(EndReason.ABUSIVE)),
        Arguments.of(
            "abuse over a prompt that does not load",
            "...",
            HARASSMENT,
            OPEN,
            new ModelAccess(false, false, true, true),
            Step.ABUSE_SCORE,
            new PreGuards.EndAbusive(Detector.MODERATION)),
        Arguments.of(
            "a prompt that does not load over no model",
            "hi",
            CLEAN,
            OPEN,
            new ModelAccess(false, false, true, true),
            Step.PINNED_PROMPT,
            new PreGuards.HandoffNoModel(NoModelReason.PROMPT)),
        Arguments.of(
            "no model over the app cap",
            "hi",
            CLEAN,
            OPEN,
            new ModelAccess(true, false, true, true),
            Step.MODEL_KEY,
            new PreGuards.HandoffNoModel(NoModelReason.NO_MODEL)),
        Arguments.of(
            "the app cap over the conversation cap",
            "hi",
            CLEAN,
            OPEN,
            new ModelAccess(true, true, true, true),
            Step.SPEND_CAP,
            new PreGuards.HandoffNoModel(NoModelReason.SPEND_CAP_APP)),
        Arguments.of(
            "the conversation cap over a soft end",
            "hi",
            CLEAN,
            new Standing(EndReason.DECLINED, false, false, 1, false, HandoffReason.NONE),
            new ModelAccess(true, true, false, true),
            Step.SPEND_CAP,
            new PreGuards.HandoffNoModel(NoModelReason.SPEND_CAP_CONVERSATION)),
        Arguments.of(
            "a soft end over the turn cap",
            "ok thanks",
            CLEAN,
            new Standing(EndReason.DECLINED, false, false, 15, false, HandoffReason.NONE),
            MODEL,
            Step.SOFT_END,
            new PreGuards.ClassifyOnly()),
        Arguments.of(
            "the turn cap over a model call",
            "and?",
            CLEAN,
            new Standing(EndReason.NONE, false, false, 15, false, HandoffReason.NONE),
            MODEL,
            Step.TURN_CAP,
            new PreGuards.FixedClose(Template.MAX_TURNS_CLOSE, EndReason.MAX_TURNS)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("precedence")
  void earlierCheckWins(
      String rule,
      String text,
      ModerationVerdict verdict,
      Standing standing,
      ModelAccess model,
      Step decider,
      Outcome expected) {
    PreGuards.Ruling ruling = GUARDS.decide(text, verdict, standing, model);
    List<Step> order = List.of(Step.values());

    assertThat(ruling.outcome()).isEqualTo(expected);
    assertThat(ruling.evaluated()).isEqualTo(order.subList(0, order.indexOf(decider) + 1));
    assertThat(ruling.decidedBy(decider)).isTrue();
  }

  @Test
  void stopAfterAnOptOutIsNotANewOne() {
    Standing unsubscribed =
        new Standing(EndReason.UNSUBSCRIBED, false, false, 1, false, HandoffReason.NONE);

    assertThat(GUARDS.decide("STOP", CLEAN, unsubscribed, MODEL).outcome())
        .isEqualTo(new PreGuards.Silence(EndReason.UNSUBSCRIBED));
  }

  /**
   * CANCEL means the call only while one is booked and the lead has not opted out or ended hard.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("cancelWithoutAChangeableBooking")
  void cancelOtherwiseIsAStop(String state, Standing standing, Outcome expected) {
    assertThat(GUARDS.decide("CANCEL", CLEAN, standing, MODEL).outcome()).isEqualTo(expected);
  }

  static Stream<Arguments> cancelWithoutAChangeableBooking() {
    return Stream.of(
        Arguments.of("no booking", OPEN, new PreGuards.Unsubscribe(Suppression.Reason.KEYWORD)),
        Arguments.of(
            "booked, then abusive",
            new Standing(EndReason.ABUSIVE, false, false, 4, true, HandoffReason.NONE),
            new PreGuards.Unsubscribe(Suppression.Reason.KEYWORD)),
        Arguments.of(
            "booked, then opted out",
            new Standing(EndReason.UNSUBSCRIBED, false, false, 4, true, HandoffReason.NONE),
            new PreGuards.Silence(EndReason.UNSUBSCRIBED)));
  }

  /** The change was acknowledged once; CANCEL again gets silence, not a second SMS. */
  @Test
  void repeatedLoneCancelAfterTheChangeIsSuppressed() {
    Standing changing =
        new Standing(EndReason.BOOKED, false, false, 5, true, HandoffReason.RESCHEDULE);

    assertThat(GUARDS.decide("CANCEL", CLEAN, changing, MODEL).outcome())
        .isEqualTo(new PreGuards.SuppressRepeat());
  }

  @Test
  void bookingWithProseGoesToTheModel() {
    assertThat(GUARDS.decide("Cancel that, Thursday's better", CLEAN, BOOKED, MODEL).outcome())
        .isEqualTo(new PreGuards.ClassifyOnly());
  }

  @Test
  void selfHarmAfterAHardEndOnlyOnce() {
    assertThat(
            GUARDS
                .decide(
                    "...",
                    SELF_HARM,
                    new Standing(
                        EndReason.UNSUBSCRIBED, false, false, 1, false, HandoffReason.NONE),
                    MODEL)
                .outcome())
        .isEqualTo(new PreGuards.SafetyReply());
    assertThat(
            GUARDS
                .decide(
                    "...",
                    SELF_HARM,
                    new Standing(EndReason.UNSUBSCRIBED, false, true, 2, false, HandoffReason.NONE),
                    MODEL)
                .outcome())
        .isEqualTo(new PreGuards.Silence(EndReason.UNSUBSCRIBED));
  }

  /** Each threshold is reached at its value; only an answering endpoint's scores count. */
  @ParameterizedTest(name = "{0}/{1}/{2} available {3}: {4}")
  @CsvSource({
    "0.5,  0,    0,    true,  EndAbusive",
    "0,    0.5,  0,    true,  EndAbusive",
    "0,    0,    0.3,  true,  SafetyReply",
    "0.49, 0.49, 0.29, true,  Proceed",
    "0.9,  0.9,  0.9,  false, Proceed"
  })
  void moderationThresholds(
      double harassment, double threat, double selfHarm, boolean available, String expected) {
    ModerationVerdict verdict = new ModerationVerdict(harassment, threat, selfHarm, available);

    assertThat(GUARDS.decide("...", verdict, OPEN, MODEL).outcome().getClass().getSimpleName())
        .isEqualTo(expected);
  }

  /** A turn that reaches the model ran every step, each a pass, in this order. */
  @Test
  void turnBelowTheCapProceedsPastEveryStep() {
    PreGuards.Ruling ruling =
        GUARDS.decide(
            "and?",
            CLEAN,
            new Standing(EndReason.NONE, false, false, 14, false, HandoffReason.NONE),
            MODEL);

    assertThat(ruling.outcome()).isEqualTo(new PreGuards.Proceed());
    assertThat(ruling.evaluated())
        .extracting(Step::traceName)
        .isEqualTo(
            List.of(
                "booking-change",
                "stop-keyword",
                "self-harm",
                "terminal-check",
                "reset",
                "abuse-score",
                "pinned-prompt",
                "model-key",
                "spend-cap",
                "soft-end",
                "turn-cap"));
    assertThat(ruling.evaluated()).noneMatch(ruling::decidedBy);
  }

  private static ModerationVerdict scores(double harassment, double threat, double selfHarm) {
    return new ModerationVerdict(harassment, threat, selfHarm, true);
  }
}
