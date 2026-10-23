package com.enrola.anna.guard;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.config.LimitsProperties;
import com.enrola.anna.config.ModerationProperties;
import com.enrola.anna.guard.PreGuards.Detector;
import com.enrola.anna.guard.PreGuards.ModelAccess;
import com.enrola.anna.guard.PreGuards.NoModelReason;
import com.enrola.anna.guard.PreGuards.Outcome;
import com.enrola.anna.guard.PreGuards.Standing;
import com.enrola.anna.lead.Suppression;
import com.enrola.anna.prompt.Template;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** The pre-guard order with limits 15/3/1 and thresholds 0.5/0.5/0.3. */
class PreGuardsTest {

  static final PreGuards GUARDS =
      new PreGuards(
          new LimitsProperties(15, 3, 1),
          new ModerationProperties(0.5, 0.5, 0.3, Duration.ofSeconds(5), 0));
  static final Standing OPEN = new Standing(EndReason.NONE, false, 0);
  static final ModelAccess MODEL = new ModelAccess(true, false, false);
  static final ModerationVerdict CLEAN = ScriptedModerationClient.CLEAN;
  static final ModerationVerdict SELF_HARM = scores(0, 0, 0.9);
  static final ModerationVerdict HARASSMENT = scores(0.9, 0, 0);

  /** Each row matches two adjacent checks; the earlier one decides. */
  static Stream<Arguments> precedence() {
    return Stream.of(
        Arguments.of(
            "STOP over self-harm",
            "STOP",
            SELF_HARM,
            OPEN,
            MODEL,
            new PreGuards.Unsubscribe(Suppression.Reason.KEYWORD)),
        Arguments.of(
            "self-harm over a hard end",
            "...",
            SELF_HARM,
            new Standing(EndReason.ABUSIVE, false, 1),
            MODEL,
            new PreGuards.SafetyReply()),
        Arguments.of(
            "a hard end over abuse",
            "...",
            HARASSMENT,
            new Standing(EndReason.ABUSIVE, false, 1),
            MODEL,
            new PreGuards.Silence(EndReason.ABUSIVE)),
        Arguments.of(
            "abuse over no model",
            "...",
            HARASSMENT,
            OPEN,
            new ModelAccess(false, true, true),
            new PreGuards.EndAbusive(Detector.MODERATION)),
        Arguments.of(
            "no model over the app cap",
            "hi",
            CLEAN,
            OPEN,
            new ModelAccess(false, true, true),
            new PreGuards.HandoffNoModel(NoModelReason.NO_MODEL)),
        Arguments.of(
            "the app cap over the conversation cap",
            "hi",
            CLEAN,
            OPEN,
            new ModelAccess(true, true, true),
            new PreGuards.HandoffNoModel(NoModelReason.SPEND_CAP_APP)),
        Arguments.of(
            "the conversation cap over a soft end",
            "hi",
            CLEAN,
            new Standing(EndReason.DECLINED, false, 1),
            new ModelAccess(true, false, true),
            new PreGuards.HandoffNoModel(NoModelReason.SPEND_CAP_CONVERSATION)),
        Arguments.of(
            "a soft end over the turn cap",
            "ok thanks",
            CLEAN,
            new Standing(EndReason.DECLINED, false, 15),
            MODEL,
            new PreGuards.ClassifyOnly()),
        Arguments.of(
            "the turn cap over a model call",
            "and?",
            CLEAN,
            new Standing(EndReason.NONE, false, 15),
            MODEL,
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
      Outcome expected) {
    assertThat(GUARDS.decide(text, verdict, standing, model)).isEqualTo(expected);
  }

  @Test
  void stopAfterAnOptOutIsNotANewOne() {
    Standing unsubscribed = new Standing(EndReason.UNSUBSCRIBED, false, 1);

    assertThat(GUARDS.decide("STOP", CLEAN, unsubscribed, MODEL))
        .isEqualTo(new PreGuards.Silence(EndReason.UNSUBSCRIBED));
  }

  @Test
  void selfHarmAfterAHardEndOnlyOnce() {
    assertThat(
            GUARDS.decide("...", SELF_HARM, new Standing(EndReason.UNSUBSCRIBED, false, 1), MODEL))
        .isEqualTo(new PreGuards.SafetyReply());
    assertThat(
            GUARDS.decide("...", SELF_HARM, new Standing(EndReason.UNSUBSCRIBED, true, 2), MODEL))
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

    assertThat(GUARDS.decide("...", verdict, OPEN, MODEL).getClass().getSimpleName())
        .isEqualTo(expected);
  }

  @Test
  void turnBelowTheCapProceeds() {
    assertThat(GUARDS.decide("and?", CLEAN, new Standing(EndReason.NONE, false, 14), MODEL))
        .isEqualTo(new PreGuards.Proceed());
  }

  private static ModerationVerdict scores(double harassment, double threat, double selfHarm) {
    return new ModerationVerdict(harassment, threat, selfHarm, true);
  }
}
