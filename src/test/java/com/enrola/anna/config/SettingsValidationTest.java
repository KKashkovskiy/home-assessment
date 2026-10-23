package com.enrola.anna.config;

import static com.enrola.anna.config.SettingsFixture.with;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The rules every saved record passes, on the shipped settings with one value broken at a time. */
class SettingsValidationTest {

  static final Settings DEFAULTS = SettingsFixture.DEFAULTS;

  @Test
  void shippedDefaultsPass() {
    assertThat(SettingsValidation.validate(DEFAULTS)).isEmpty();
  }

  /**
   * Each row breaks one rule at its edge; the problems name the form fields that hold the value. A
   * zero app cap also puts the per-conversation cap above it, and inverted weekday hours close
   * Saturday after the weekday close, so both fields are marked.
   */
  static Stream<Arguments> brokenRules() {
    return Stream.of(
        broken(limits("maxChars", 321), "maxChars"),
        broken(limits("maxChars", 0), "maxChars", "targetChars"),
        broken(limits("targetChars", 320), "targetChars"),
        broken(limits("targetChars", 0), "targetChars"),
        broken(limits("maxInboundChars", 319), "maxInboundChars"),
        broken(limits("maxTurns", 0), "maxTurns"),
        broken(limits("offTopicLimit", 0), "offTopicLimit"),
        broken(limits("objectionRetries", -1), "objectionRetries"),
        broken(limits("maxToolRounds", 0), "maxToolRounds"),
        broken(limits("maxToolRounds", 9), "maxToolRounds"),
        broken(limits("maxOutputTokens", 15), "maxOutputTokens"),
        broken(budgets("convCap", "20"), "convCap"),
        broken(budgets("convCap", "0"), "convCap"),
        broken(budgets("appCap", "0"), "appCap", "convCap"),
        broken(budgets("evalCap", "0"), "evalCap"),
        broken(hours(LocalTime.of(20, 0), LocalTime.of(9, 0)), "hoursTo", "satTo"),
        broken(scheduling("satTo", LocalTime.of(20, 30)), "satTo"),
        broken(scheduling("satTo", LocalTime.of(9, 0)), "satTo"),
        broken(scheduling("eventMin", 481), "eventMin"),
        broken(scheduling("eventMin", 900), "eventMin"),
        broken(scheduling("eventMin", 0), "eventMin"),
        broken(scheduling("leadTimeMin", -1), "leadTimeMin"),
        broken(scheduling("horizonDays", 32), "horizonDays"),
        broken(scheduling("horizonDays", 0), "horizonDays"),
        broken(scheduling("gridMin", 0), "gridMin"),
        broken(scheduling("maxSlots", 0), "maxSlots"),
        broken(moderation("modThreat", 1.5), "modThreat"),
        broken(moderation("modHarassment", 0.0), "modHarassment"),
        broken(moderation("modSelfHarm", -0.1), "modSelfHarm"),
        broken(settings -> settings.withClock(fixedAt(null)), "fixedClock"),
        broken(persona("persona", " "), "persona"),
        broken(persona("company", ""), "company"),
        broken(agent("model", "GPT 5"), "model"),
        broken(agent("model", "gpt-x"), "model"),
        broken(prices("gpt-5.1", "-1"), SettingsValidation.priceRowField("gpt-5.1")),
        broken(prices("gpt-5.1", null), SettingsValidation.priceRowField("gpt-5.1")),
        broken(agent("reasoningSummary", null), "reasoningSummary"));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("brokenRules")
  void brokenValueNamesItsFields(UnaryOperator<Settings> breaking, List<String> fields) {
    assertThat(SettingsValidation.validate(breaking.apply(DEFAULTS)))
        .extracting(SettingsValidation.Problem::field)
        .containsExactlyElementsOf(fields);
  }

  /** A call fits Saturday exactly, and one conversation may spend the whole app cap. */
  @Test
  void edgesThatPass() {
    Settings edges =
        with(
            with(DEFAULTS, "scheduling", "eventMin", 480),
            "budgets",
            "convCap",
            DEFAULTS.budgets().appCap());

    assertThat(SettingsValidation.validate(edges)).isEmpty();
  }

  @Test
  void everyProblemIsReported() {
    Settings threeBroken =
        with(
            with(
                with(DEFAULTS, "limits", "maxTurns", 0),
                "budgets",
                "convCap",
                BigDecimal.TEN.add(BigDecimal.ONE)),
            "scheduling",
            "horizonDays",
            45);

    assertThat(SettingsValidation.validate(threeBroken))
        .extracting(SettingsValidation.Problem::field)
        .containsExactlyInAnyOrder("maxTurns", "convCap", "horizonDays");
  }

  /** A record with a value missing, as from a hand-edited file, reports what is missing only. */
  @Test
  void missingValuesAreReportedByFieldAndNothingElse() {
    Settings missing = with(with(DEFAULTS, "agent", "reasoning", null), "budgets", "convCap", null);

    assertThat(SettingsValidation.validate(missing))
        .extracting(SettingsValidation.Problem::field)
        .containsExactly("reasoning", "convCap");
  }

  @Test
  void aRealClockNeedsNoTime() {
    assertThat(
            SettingsValidation.validate(
                DEFAULTS.withClock(new Settings.Clock(Settings.ClockMode.REAL, null))))
        .isEmpty();
  }

  private static Arguments broken(UnaryOperator<Settings> breaking, String... fields) {
    return Arguments.of(breaking, List.of(fields));
  }

  private static UnaryOperator<Settings> agent(String name, Object value) {
    return settings -> with(settings, "agent", name, value);
  }

  private static UnaryOperator<Settings> limits(String name, int value) {
    return settings -> with(settings, "limits", name, value);
  }

  private static UnaryOperator<Settings> budgets(String name, String usd) {
    return settings -> with(settings, "budgets", name, new BigDecimal(usd));
  }

  private static UnaryOperator<Settings> persona(String name, String value) {
    return settings -> with(settings, "persona", name, value);
  }

  private static UnaryOperator<Settings> scheduling(String name, Object value) {
    return settings -> with(settings, "scheduling", name, value);
  }

  private static UnaryOperator<Settings> hours(LocalTime from, LocalTime to) {
    return settings ->
        with(with(settings, "scheduling", "hoursFrom", from), "scheduling", "hoursTo", to);
  }

  private static UnaryOperator<Settings> moderation(String name, double value) {
    return settings -> with(settings, "moderation", name, value);
  }

  /** The table with only {@code model}, at {@code input} per million input tokens. */
  private static UnaryOperator<Settings> prices(String model, String input) {
    return settings ->
        with(
            settings,
            "priceTable",
            Map.of(
                model,
                new Settings.Price(
                    input == null ? null : new BigDecimal(input),
                    BigDecimal.ZERO,
                    BigDecimal.ONE)));
  }

  private static Settings.Clock fixedAt(LocalDateTime at) {
    return new Settings.Clock(Settings.ClockMode.FIXED, at);
  }
}
