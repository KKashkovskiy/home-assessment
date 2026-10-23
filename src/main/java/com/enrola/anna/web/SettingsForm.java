package com.enrola.anna.web;

import com.enrola.anna.config.Settings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * The runtime settings form: every {@link Settings} value flat, named as the prototype's form names
 * it, then the price table's rows and the quick-replies file. A value that does not bind is left
 * out, as zero or null, and the binding reports it; {@link #toSettings} hands the rest to the
 * store's rules, which report what is missing. State holidays are one comma-separated field.
 */
public record SettingsForm(
    String model,
    Settings.ReasoningEffort reasoning,
    Settings.ReasoningSummary reasoningSummary,
    int maxChars,
    int targetChars,
    int maxInboundChars,
    int maxTurns,
    int offTopicLimit,
    int objectionRetries,
    int maxToolRounds,
    int maxOutputTokens,
    BigDecimal convCap,
    BigDecimal appCap,
    BigDecimal evalCap,
    String persona,
    String company,
    boolean disclosureInOpener,
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime hoursFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime hoursTo,
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime satTo,
    int leadTimeMin,
    int horizonDays,
    ZoneId advisorZone,
    int eventMin,
    int gridMin,
    int maxSlots,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) List<LocalDate> stateHolidays,
    double modHarassment,
    double modSelfHarm,
    double modThreat,
    Settings.ClockMode clockMode,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fixedClock,
    Settings.CalendarPreset calendarPreset,
    List<PriceRow> prices,
    String quickReplies) {

  /** One price table row; a row whose model is blank is dropped, which is how a row is deleted. */
  public record PriceRow(String model, BigDecimal input, BigDecimal cached, BigDecimal output) {

    static final PriceRow BLANK = new PriceRow("", null, null, null);

    /** No model: the row for adding one, or one cleared to drop it. */
    public boolean blank() {
      return model == null || model.isBlank();
    }
  }

  /** The name a price row's cells post under, {@code prices[i]}, and its problems' key. */
  public static String rowField(int row) {
    return "prices[" + row + "]";
  }

  /** {@code settings} as the form shows them, with the quick-replies file's {@code text}. */
  static SettingsForm of(Settings settings, String text) {
    Settings.Limits limits = settings.limits();
    Settings.Budgets budgets = settings.budgets();
    Settings.Persona persona = settings.persona();
    Settings.Scheduling scheduling = settings.scheduling();
    Settings.Moderation moderation = settings.moderation();
    return new SettingsForm(
        settings.agent().model(),
        settings.agent().reasoning(),
        settings.agent().reasoningSummary(),
        limits.maxChars(),
        limits.targetChars(),
        limits.maxInboundChars(),
        limits.maxTurns(),
        limits.offTopicLimit(),
        limits.objectionRetries(),
        limits.maxToolRounds(),
        limits.maxOutputTokens(),
        budgets.convCap(),
        budgets.appCap(),
        budgets.evalCap(),
        persona.persona(),
        persona.company(),
        persona.disclosureInOpener(),
        scheduling.hoursFrom(),
        scheduling.hoursTo(),
        scheduling.satTo(),
        scheduling.leadTimeMin(),
        scheduling.horizonDays(),
        scheduling.advisorZone(),
        scheduling.eventMin(),
        scheduling.gridMin(),
        scheduling.maxSlots(),
        scheduling.stateHolidays(),
        moderation.modHarassment(),
        moderation.modSelfHarm(),
        moderation.modThreat(),
        settings.clock().clockMode(),
        settings.clock().fixedClock(),
        settings.calendarPreset(),
        rowsOf(settings.priceTable()),
        text);
  }

  /** The record these fields describe; text is stripped, blank price rows are dropped. */
  Settings toSettings() {
    return new Settings(
        new Settings.Agent(strip(model), reasoning, reasoningSummary),
        new Settings.Limits(
            maxChars,
            targetChars,
            maxInboundChars,
            maxTurns,
            offTopicLimit,
            objectionRetries,
            maxToolRounds,
            maxOutputTokens),
        new Settings.Budgets(convCap, appCap, evalCap),
        new Settings.Persona(strip(persona), strip(company), disclosureInOpener),
        new Settings.Scheduling(
            hoursFrom,
            hoursTo,
            satTo,
            leadTimeMin,
            horizonDays,
            advisorZone,
            eventMin,
            gridMin,
            maxSlots,
            stateHolidays),
        new Settings.Moderation(modHarassment, modSelfHarm, modThreat),
        new Settings.Clock(clockMode, fixedClock),
        calendarPreset,
        priceTable());
  }

  /** "2026-11-03, 2027-03-10", as the holidays field shows and posts them. */
  public String stateHolidaysText() {
    return stateHolidays == null
        ? ""
        : stateHolidays.stream().map(LocalDate::toString).collect(Collectors.joining(", "));
  }

  /**
   * The rows to edit, as posted or as the table has them, ending with a blank one for adding a
   * model; a row's index is the one its cells post under.
   */
  public List<PriceRow> priceRows() {
    List<PriceRow> rows = prices == null ? List.of() : prices;
    return !rows.isEmpty() && rows.getLast().blank()
        ? rows
        : Stream.concat(rows.stream(), Stream.of(PriceRow.BLANK)).toList();
  }

  /** A model named twice keeps its last row, as the form lists it. */
  private Map<String, Settings.Price> priceTable() {
    Map<String, Settings.Price> table = new LinkedHashMap<>();
    priceRows().stream()
        .filter(row -> !row.blank())
        .forEach(
            row ->
                table.put(
                    row.model().strip(),
                    new Settings.Price(row.input(), row.cached(), row.output())));
    return table;
  }

  private static List<PriceRow> rowsOf(Map<String, Settings.Price> priceTable) {
    return priceTable.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            row ->
                new PriceRow(
                    row.getKey(),
                    row.getValue().input(),
                    row.getValue().cached(),
                    row.getValue().output()))
        .toList();
  }

  private static String strip(String text) {
    return text == null ? null : text.strip();
  }
}
