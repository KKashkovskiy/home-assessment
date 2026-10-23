package com.enrola.anna.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every value the owner can change while the app runs, as one immutable record. {@link
 * SettingsStore} swaps the whole record on a valid save; a turn reads it once at its start and
 * hands each step the nested record it needs, so a save never changes a turn in flight. The clock
 * and the calendar preset are the exception: they apply the moment a save lands. {@code
 * application.yml} holds the defaults and {@code data/settings.json} the overrides. Component names
 * are the settings form's field names, so a problem names the field to mark. Keys are never here.
 */
public record Settings(
    Agent agent,
    Limits limits,
    Budgets budgets,
    Persona persona,
    Scheduling scheduling,
    Moderation moderation,
    Clock clock,
    CalendarPreset calendarPreset,
    Map<String, Price> priceTable) {

  /** The model every call goes to, how hard it reasons, and the summary of its reasoning. */
  public record Agent(String model, ReasoningEffort reasoning, ReasoningSummary reasoningSummary) {}

  /** The reasoning effort sent with each call; lowercase wherever it is stored or posted. */
  public enum ReasoningEffort {
    LOW,
    MEDIUM,
    HIGH;

    /** The lowercase name the API, the files and the forms use. */
    @JsonValue
    public String value() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** The effort {@code value} names, in any case; an unknown name throws. */
    @JsonCreator
    public static ReasoningEffort of(String value) {
      return valueOf(value.toUpperCase(Locale.ROOT));
    }
  }

  /**
   * The summary of its reasoning the model is asked for, kept in the trace and never sent to the
   * lead; OFF asks for none. Lowercase wherever it is stored or posted.
   */
  public enum ReasoningSummary {
    AUTO,
    CONCISE,
    DETAILED,
    OFF;

    /** The lowercase name the files and the forms use. */
    @JsonValue
    public String value() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** The summary {@code value} names, in any case; an unknown name throws. */
    @JsonCreator
    public static ReasoningSummary of(String value) {
      return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /** What a call asks the API for: the lowercase name, or null for OFF, which asks for none. */
    public String requested() {
      return this == OFF ? null : value();
    }
  }

  /**
   * The lengths and counts code enforces around the model. A reply over {@code maxChars} is
   * rejected, and {@code targetChars} is what the prompt asks for; an inbound over {@code
   * maxInboundChars} is stored whole, flagged and cut for the model. {@code maxTurns} counts agent
   * messages after the opener, {@code offTopicLimit} is the OFF_TOPIC or INJECTION streak that ends
   * a conversation, and {@code objectionRetries} the objections that get a retry before the next
   * one ends it DECLINED. {@code maxToolRounds} caps the tool rounds of a turn, its decision call
   * and its one repair together, and {@code maxOutputTokens} the output of each call.
   */
  public record Limits(
      int maxChars,
      int targetChars,
      int maxInboundChars,
      int maxTurns,
      int offTopicLimit,
      int objectionRetries,
      int maxToolRounds,
      int maxOutputTokens) {}

  /**
   * Spend caps in USD against the sum of {@code turn.cost_usd}: one conversation's, the app's, and
   * one eval run's, which has a ledger of its own. At or above a cap no model call is made.
   */
  public record Budgets(BigDecimal convCap, BigDecimal appCap, BigDecimal evalCap) {}

  /**
   * Who is texting: {@code persona} is the agent's name, {@code company} is named in the opener,
   * and {@code disclosureInOpener} picks ", Company's AI assistant" over " from Company".
   */
  public record Persona(String persona, String company, boolean disclosureInOpener) {}

  /**
   * How call slots are generated and offered, on the advisors' clock in {@code advisorZone}, which
   * is also the zone of a lead whose state is unknown. Weekdays run from {@code hoursFrom} to
   * {@code hoursTo} and Saturday to {@code satTo}; Sunday and the national and {@code
   * stateHolidays} are closed. A call lasts {@code eventMin} on a {@code gridMin} grid, the first
   * slot is {@code leadTimeMin} from now, the last within {@code horizonDays}, and one offer has at
   * most {@code maxSlots}.
   */
  public record Scheduling(
      LocalTime hoursFrom,
      LocalTime hoursTo,
      LocalTime satTo,
      int leadTimeMin,
      int horizonDays,
      ZoneId advisorZone,
      int eventMin,
      int gridMin,
      int maxSlots,
      List<LocalDate> stateHolidays) {}

  /**
   * Moderation score thresholds, each reached at or above its value: harassment and threat end the
   * conversation ABUSIVE, self-harm sends the fixed safety template.
   */
  public record Moderation(double modHarassment, double modSelfHarm, double modThreat) {}

  /**
   * Where "now" comes from: the system clock, or {@code fixedClock}, advisor-local wall time, which
   * a FIXED clock requires.
   */
  public record Clock(ClockMode clockMode, LocalDateTime fixedClock) {}

  /** REAL follows the system clock; FIXED stands still. */
  public enum ClockMode {
    REAL,
    FIXED
  }

  /**
   * How the fake calendar behaves, for testing the paths a real one would take: as usual, with
   * nothing free, or with the next booking answered as taken. Written lowercase wherever it is
   * stored or posted: {@code normal}, {@code fully_booked}, {@code slot_taken}.
   */
  public enum CalendarPreset {
    NORMAL,
    FULLY_BOOKED,
    SLOT_TAKEN;

    /** The lowercase name the settings file, the yml and the forms use. */
    @JsonValue
    public String value() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** The preset {@code value} names, in any case; an unknown name throws. */
    @JsonCreator
    public static CalendarPreset of(String value) {
      return valueOf(value.toUpperCase(Locale.ROOT));
    }
  }

  /** USD per one million tokens: uncached input, cached input, and output. */
  public record Price(BigDecimal input, BigDecimal cached, BigDecimal output) {}

  /** These settings with the clock replaced, as the status strip's clock control saves them. */
  public Settings withClock(Clock replacement) {
    return new Settings(
        agent,
        limits,
        budgets,
        persona,
        scheduling,
        moderation,
        replacement,
        calendarPreset,
        priceTable);
  }

  /** These settings with the preset replaced, as the status strip's calendar control saves them. */
  public Settings withCalendarPreset(CalendarPreset replacement) {
    return new Settings(
        agent, limits, budgets, persona, scheduling, moderation, clock, replacement, priceTable);
  }
}
