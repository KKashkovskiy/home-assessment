package com.enrola.anna.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code anna.*} groups of {@code application.yml} as written there, bound once at boot. {@link
 * #settings()} is the first {@link Settings}, which {@code data/settings.json} may override and
 * {@link SettingsValidation} checks. The rest is client wiring a save cannot change, the timeouts,
 * retries and strict model check, read once when the clients are built; a missing one fails startup
 * here.
 */
@Validated
@ConfigurationProperties(prefix = "anna")
public record SettingsDefaults(
    @NotNull @Valid Agent agent,
    @NotNull Sms sms,
    @NotNull Persona persona,
    @NotNull Limits limits,
    @NotNull Budgets budgets,
    @NotNull @Valid Moderation moderation,
    @NotNull Scheduling scheduling,
    @NotNull Clock clock,
    Map<String, Settings.Price> prices) {

  /**
   * {@code anna.agent}: the model and how it is called. {@code timeout} bounds each HTTP attempt
   * and the SDK retries timeouts and 5xx, so a call makes up to {@code 1 + maxRetries} attempts;
   * {@code strictModelCheck} turns a failed boot-time check into a failed boot.
   */
  public record Agent(
      String model,
      Settings.ReasoningEffort reasoningEffort,
      Settings.ReasoningSummary reasoningSummary,
      @NotNull Duration timeout,
      @PositiveOrZero int maxRetries,
      int maxOutputTokens,
      int maxToolRounds,
      boolean strictModelCheck) {}

  /** {@code anna.sms}. */
  public record Sms(int maxChars, int targetChars, int maxInboundChars) {}

  /** {@code anna.persona}; the disclosure is on unless the yml says otherwise. */
  public record Persona(
      String name, String company, @DefaultValue("true") boolean disclosureInOpener) {}

  /** {@code anna.limits}. */
  public record Limits(int maxTurns, int offTopicLimit, int objectionRetries) {}

  /** {@code anna.budgets}. */
  public record Budgets(
      BigDecimal appCapUsd, BigDecimal conversationCapUsd, BigDecimal evalCapUsd) {}

  /**
   * {@code anna.moderation}: the thresholds, and the moderation call's own timeout and retries, so
   * a slow endpoint delays a turn by seconds, not by the model's budget.
   */
  public record Moderation(
      double harassment,
      double threat,
      double selfHarm,
      @NotNull Duration timeout,
      @PositiveOrZero int maxRetries) {}

  /** {@code anna.scheduling}. */
  public record Scheduling(
      ZoneId advisorZone,
      int leadTimeMin,
      int horizonDays,
      @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime hoursFrom,
      @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime hoursTo,
      @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime satTo,
      int eventMin,
      int gridMin,
      int maxSlots,
      Settings.CalendarPreset calendarPreset,
      List<LocalDate> stateHolidays) {}

  /** {@code anna.clock}: {@code fixedAt} is an instant, read when {@code mode} is FIXED. */
  public record Clock(Settings.ClockMode mode, Instant fixedAt) {}

  /** The yml as one {@link Settings}; the fixed instant becomes advisor-local wall time. */
  public Settings settings() {
    return new Settings(
        new Settings.Agent(agent.model(), agent.reasoningEffort(), agent.reasoningSummary()),
        new Settings.Limits(
            sms.maxChars(),
            sms.targetChars(),
            sms.maxInboundChars(),
            limits.maxTurns(),
            limits.offTopicLimit(),
            limits.objectionRetries(),
            agent.maxToolRounds(),
            agent.maxOutputTokens()),
        new Settings.Budgets(
            budgets.conversationCapUsd(), budgets.appCapUsd(), budgets.evalCapUsd()),
        new Settings.Persona(persona.name(), persona.company(), persona.disclosureInOpener()),
        new Settings.Scheduling(
            scheduling.hoursFrom(),
            scheduling.hoursTo(),
            scheduling.satTo(),
            scheduling.leadTimeMin(),
            scheduling.horizonDays(),
            scheduling.advisorZone(),
            scheduling.eventMin(),
            scheduling.gridMin(),
            scheduling.maxSlots(),
            scheduling.stateHolidays()),
        new Settings.Moderation(
            moderation.harassment(), moderation.selfHarm(), moderation.threat()),
        new Settings.Clock(clock.mode(), fixedClock()),
        scheduling.calendarPreset(),
        prices);
  }

  private LocalDateTime fixedClock() {
    if (clock.fixedAt() == null || scheduling.advisorZone() == null) {
      return null;
    }
    return LocalDateTime.ofInstant(clock.fixedAt(), scheduling.advisorZone());
  }
}
