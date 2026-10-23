package com.enrola.anna.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The rules a {@link Settings} must pass to run: pure, and every problem is reported, not the
 * first, each named by the form field it belongs to. A record with a value missing, as from a
 * settings file with a section deleted by hand, reports what is missing and nothing else, since the
 * rules need every value.
 */
public final class SettingsValidation {

  /** What is wrong with one value, and the form field that holds it. */
  public record Problem(String field, String message) {}

  /** The brief's hard reply limit: a setting may lower it, never raise it. */
  public static final int SMS_LIMIT = 320;

  public static final int MAX_TOOL_ROUNDS = 8;

  /** The fewest output tokens the Responses API accepts. */
  public static final int MIN_OUTPUT_TOKENS = 16;

  /** Calendly's own range: no slot is offered further ahead. */
  public static final int CALENDAR_RANGE_DAYS = 31;

  private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9.-]+");

  /** The one value that may be missing: a real clock need not have stood still yet. */
  private static final Set<String> OPTIONAL = Set.of("fixedClock");

  private SettingsValidation() {}

  /** One check on one field; {@code broken} when the value fails it. */
  private record Rule(String field, boolean broken, String message) {}

  public static List<Problem> validate(Settings settings) {
    List<Problem> missing = missing(settings).toList();
    if (!missing.isEmpty()) {
      return missing;
    }
    return Stream.of(
            agent(settings),
            limits(settings.limits()),
            budgets(settings.budgets()),
            persona(settings.persona()),
            scheduling(settings.scheduling()),
            moderation(settings.moderation()),
            clock(settings.clock()),
            prices(settings.priceTable()))
        .flatMap(List::stream)
        .filter(Rule::broken)
        .map(rule -> new Problem(rule.field(), rule.message()))
        .toList();
  }

  /** The form field of the price table row for {@code model}. */
  public static String priceRowField(String model) {
    return "priceTable[" + model + "]";
  }

  /** Each call is costed, so the model needs a price row. */
  private static List<Rule> agent(Settings settings) {
    String model = settings.agent().model();
    boolean wellFormed = MODEL_ID.matcher(model).matches();
    return List.of(
        new Rule("model", !wellFormed, "Model ids are lowercase letters, digits, dots and dashes."),
        new Rule(
            "model",
            wellFormed && !settings.priceTable().containsKey(model),
            "No price row for this model; add one to the price table."));
  }

  private static List<Rule> limits(Settings.Limits limits) {
    return List.of(
        new Rule(
            "maxChars",
            limits.maxChars() < 1 || limits.maxChars() > SMS_LIMIT,
            "Hard limit must be between 1 and " + SMS_LIMIT + "."),
        new Rule(
            "targetChars",
            limits.targetChars() < 1 || limits.targetChars() >= limits.maxChars(),
            "Target must be at least 1 and below the hard limit."),
        new Rule(
            "maxInboundChars",
            limits.maxInboundChars() < limits.maxChars(),
            "Must be at least the hard limit."),
        new Rule("maxTurns", limits.maxTurns() < 1, "At least 1 turn."),
        new Rule("offTopicLimit", limits.offTopicLimit() < 1, "At least 1."),
        new Rule("objectionRetries", limits.objectionRetries() < 0, "Cannot be negative."),
        new Rule(
            "maxToolRounds",
            limits.maxToolRounds() < 1 || limits.maxToolRounds() > MAX_TOOL_ROUNDS,
            "Between 1 and " + MAX_TOOL_ROUNDS + "."),
        new Rule(
            "maxOutputTokens",
            limits.maxOutputTokens() < MIN_OUTPUT_TOKENS,
            "At least " + MIN_OUTPUT_TOKENS + " tokens."));
  }

  /** A per-conversation cap equal to the app cap is allowed: one conversation may spend it all. */
  private static List<Rule> budgets(Settings.Budgets budgets) {
    return List.of(
        new Rule("convCap", budgets.convCap().signum() <= 0, "Cap must be above zero."),
        new Rule("appCap", budgets.appCap().signum() <= 0, "Cap must be above zero."),
        new Rule("evalCap", budgets.evalCap().signum() <= 0, "Cap must be above zero."),
        new Rule(
            "convCap",
            budgets.convCap().compareTo(budgets.appCap()) > 0,
            "Per-conversation cap cannot exceed the app cap."));
  }

  private static List<Rule> persona(Settings.Persona persona) {
    return List.of(
        new Rule("persona", persona.persona().isBlank(), "The agent needs a name."),
        new Rule("company", persona.company().isBlank(), "The company needs a name."));
  }

  /**
   * Saturday closes after opening and no later than a weekday, and a call fits between opening and
   * the earlier close. The fit is measured in minutes, since times past midnight wrap.
   */
  private static List<Rule> scheduling(Settings.Scheduling scheduling) {
    LocalTime opens = scheduling.hoursFrom();
    long shortestDay =
        Math.min(
            minutesBetween(opens, scheduling.satTo()), minutesBetween(opens, scheduling.hoursTo()));
    return List.of(
        new Rule(
            "hoursTo",
            !opens.isBefore(scheduling.hoursTo()),
            "Closing time must be after opening time."),
        new Rule(
            "satTo",
            !opens.isBefore(scheduling.satTo()) || scheduling.satTo().isAfter(scheduling.hoursTo()),
            "Saturday must close after opening and no later than a weekday."),
        new Rule(
            "eventMin",
            scheduling.eventMin() < 1 || (shortestDay > 0 && scheduling.eventMin() > shortestDay),
            "A call must fit between opening and closing, Saturday included."),
        new Rule("leadTimeMin", scheduling.leadTimeMin() < 0, "Lead time cannot be negative."),
        new Rule(
            "horizonDays",
            scheduling.horizonDays() < 1 || scheduling.horizonDays() > CALENDAR_RANGE_DAYS,
            "Between 1 and " + CALENDAR_RANGE_DAYS + " days; Calendly allows no more."),
        new Rule("gridMin", scheduling.gridMin() < 1, "At least 1 minute."),
        new Rule("maxSlots", scheduling.maxSlots() < 1, "At least 1 slot."));
  }

  private static long minutesBetween(LocalTime from, LocalTime to) {
    return Duration.between(from, to).toMinutes();
  }

  /** A threshold of 0 would trip on every message, so each is above 0 and at most 1. */
  private static List<Rule> moderation(Settings.Moderation moderation) {
    return Stream.of(
            Map.entry("modHarassment", moderation.modHarassment()),
            Map.entry("modSelfHarm", moderation.modSelfHarm()),
            Map.entry("modThreat", moderation.modThreat()))
        .map(
            threshold ->
                new Rule(
                    threshold.getKey(),
                    !(threshold.getValue() > 0 && threshold.getValue() <= 1),
                    "Above 0 and at most 1."))
        .toList();
  }

  private static List<Rule> clock(Settings.Clock clock) {
    return List.of(
        new Rule(
            "fixedClock",
            clock.clockMode() == Settings.ClockMode.FIXED && clock.fixedClock() == null,
            "A fixed clock needs a time."));
  }

  private static List<Rule> prices(Map<String, Settings.Price> priceTable) {
    return priceTable.entrySet().stream()
        .map(
            row ->
                new Rule(
                    priceRowField(row.getKey()),
                    row.getKey().isBlank() || !priced(row.getValue()),
                    "Each row needs a model and three prices of 0 or more."))
        .toList();
  }

  private static boolean priced(Settings.Price price) {
    return price != null
        && Stream.of(price.input(), price.cached(), price.output())
            .allMatch(usd -> usd != null && usd.signum() >= 0);
  }

  /**
   * Each missing value of {@code record} and the records within it, by component name, which is the
   * form field's name.
   */
  private static Stream<Problem> missing(Record record) {
    return Arrays.stream(record.getClass().getRecordComponents())
        .filter(component -> !OPTIONAL.contains(component.getName()))
        .flatMap(
            component ->
                switch (valueOf(component, record)) {
                  case null -> Stream.of(new Problem(component.getName(), "Required."));
                  case Record nested -> missing(nested);
                  default -> Stream.empty();
                });
  }

  private static Object valueOf(RecordComponent component, Record record) {
    try {
      return component.getAccessor().invoke(record);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException("cannot read " + component.getName(), e);
    }
  }
}
