package com.enrola.anna.web;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsStore;
import com.enrola.anna.config.SettingsValidation;
import com.enrola.anna.conversation.ConversationStatus;
import com.enrola.anna.conversation.Source;
import com.enrola.anna.conversation.TraceOutcome;
import com.enrola.anna.conversation.TurnStatus;
import com.enrola.anna.scheduling.LeadZones;
import com.enrola.anna.scheduling.ToolResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The console's words, icons, CSS classes, option lists and time formats, which the templates call
 * as {@code @fmt}, so each has one source and the templates hold no rules. A colour or icon only
 * hints; the text beside it always says the state.
 */
@Component("fmt")
public class ViewFormats {

  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
  private static final List<String> PROVIDERS = List.of("Bupa", "HBF", "Medibank", "nib", "HCF");

  private final SettingsStore settings;

  public ViewFormats(SettingsStore settings) {
    this.settings = settings;
  }

  public List<ConversationStatus> statuses() {
    return List.of(ConversationStatus.values());
  }

  public List<EndReason> endReasons() {
    return List.of(EndReason.values());
  }

  public List<Stage> stages() {
    return List.of(Stage.values());
  }

  public List<Flag> flags() {
    return List.of(Flag.values());
  }

  public List<Source> sources() {
    return List.of(Source.values());
  }

  /** The states and territories the start form offers, beside "unknown". */
  public List<String> states() {
    return LeadZones.states();
  }

  public List<String> providers() {
    return PROVIDERS;
  }

  /** The Configuration page's File select. */
  public List<PromptFile> promptFiles() {
    return List.of(PromptFile.values());
  }

  public List<Settings.ReasoningEffort> reasoningEfforts() {
    return List.of(Settings.ReasoningEffort.values());
  }

  public List<Settings.ReasoningSummary> reasoningSummaries() {
    return List.of(Settings.ReasoningSummary.values());
  }

  public List<Settings.ClockMode> clockModes() {
    return List.of(Settings.ClockMode.values());
  }

  public List<Settings.CalendarPreset> calendarPresets() {
    return List.of(Settings.CalendarPreset.values());
  }

  /** The zones the advisor zone field suggests: those of the states and territories. */
  public List<String> zones() {
    return LeadZones.zones();
  }

  /** "fully booked": a preset as its selects name it. */
  public String presetName(Settings.CalendarPreset preset) {
    return preset.value().replace('_', ' ');
  }

  /** What a preset does to the fake calendar. */
  public String presetDescription(Settings.CalendarPreset preset) {
    return switch (preset) {
      case NORMAL -> "slots from the advisor hours";
      case FULLY_BOOKED -> "no slots at all";
      case SLOT_TAKEN -> "the next booking is refused as taken";
    };
  }

  /** "0.50", "0.125": a USD amount for a form field, at least to the cent, never rounded. */
  public String dollars(BigDecimal usd) {
    return usd == null ? "" : usd.setScale(Math.max(usd.scale(), 2)).toPlainString();
  }

  /** The longest reply any setting allows. */
  public int smsLimit() {
    return SettingsValidation.SMS_LIMIT;
  }

  public int maxToolRounds() {
    return SettingsValidation.MAX_TOOL_ROUNDS;
  }

  public int minOutputTokens() {
    return SettingsValidation.MIN_OUTPUT_TOKENS;
  }

  public int calendarRangeDays() {
    return SettingsValidation.CALENDAR_RANGE_DAYS;
  }

  public int quickReplyLines() {
    return QuickReplies.MAX_LINES;
  }

  public int quickReplyChars() {
    return QuickReplies.MAX_LINE_CHARS;
  }

  public String statusClass(ConversationStatus status) {
    return switch (status) {
      case BOOKED -> "pass";
      case ABUSIVE, SAFETY -> "fail";
      case UNSUBSCRIBED, HANDOFF -> "warn";
      case ACTIVE, DECLINED, OFF_TOPIC_LIMIT, MAX_TURNS, RESET -> "";
    };
  }

  public String statusIcon(ConversationStatus status) {
    return switch (status) {
      case ACTIVE -> "◌";
      case BOOKED -> "✓";
      case DECLINED, OFF_TOPIC_LIMIT, MAX_TURNS -> "–";
      case UNSUBSCRIBED -> "⊘";
      case ABUSIVE -> "✕";
      case SAFETY -> "♥";
      case HANDOFF -> "⚑";
      case RESET -> "↺";
    };
  }

  /** The tracker's and the list's word for a stage. */
  public String stage(Stage stage) {
    return switch (stage) {
      case ENGAGE -> "Engage";
      case PREFERENCE -> "Preference";
      case CALL_ASK -> "Call ask";
      case OFFER_TIMES -> "Offer times";
      case BOOKED -> "Booked";
    };
  }

  public String stepClass(ConsoleView.StepState state) {
    return switch (state) {
      case DONE -> "done";
      case SKIPPED -> "skipped";
      case CURRENT -> "current";
      case AHEAD -> "";
    };
  }

  /** What a screen reader hears before a stage's name, since its class is only a colour. */
  public String stepSpoken(ConsoleView.StepState state) {
    return switch (state) {
      case DONE -> "completed: ";
      case SKIPPED -> "skipped: ";
      case CURRENT -> "current: ";
      case AHEAD -> "";
    };
  }

  /** Flags a person should look at are warn-coloured. */
  public String flagClass(Flag flag) {
    return switch (flag) {
      case HEALTH_INFO, VULNERABLE, COMPLAINT, TZ_ASSUMED -> "warn";
      case AI_DISCLOSURE_ASKED,
          INJECTION_ATTEMPT,
          OFF_TOPIC,
          OBJECTION,
          LENGTH_REPAIRED,
          INPUT_TRUNCATED,
          LOOP_SUPPRESSED ->
          "";
    };
  }

  public String badgeClass(ConsoleView.Badge badge) {
    return switch (badge.tone()) {
      case PLAIN -> "chip";
      case PASS -> "chip pass";
      case WARN -> "chip warn";
    };
  }

  public String yesNo(boolean value) {
    return value ? "yes" : "no";
  }

  /** "c-0ee0ee47…": enough of an id to tell conversations apart; the full id is its title. */
  public String shortId(String id) {
    return id.length() <= 11 ? id : id.substring(0, 10) + "…";
  }

  /** A fallback reply is a warning; any other source a quiet label. */
  public String sourceClass(ReplySource source) {
    return source == ReplySource.FALLBACK ? "chip warn" : "chip ghost";
  }

  public String noticeClass(Notice notice) {
    return switch (notice.level()) {
      case PASS -> "notice pass";
      case INFO -> "notice info";
      case WARN -> "notice warn";
      case FAIL -> "notice fail";
    };
  }

  /** The line under the composer: what the next message as the lead gets. */
  public String hint(ConsoleView view) {
    String ended = "Conversation ended (" + view.conversation().status + ").";
    return switch (view.hint()) {
      case READY ->
          "Enter sends, Shift+Enter adds a line. Delivery is the platform's job; this console"
              + " plays the lead's phone.";
      case HANDED_OFF ->
          "Handed to a human ("
              + view.conversation().handoffReason
              + "). The agent still classifies; replies may be suppressed.";
      case CLOSING_ONLY ->
          ended + " The agent only classifies now: one closing reply at most, then silence.";
      case SILENT -> ended + " Messages are stored and get no reply, so you can verify silence.";
    };
  }

  /** Why a turn sent nothing, in words; a turn stored before reasons were just says none went. */
  public String systemLine(ThreadItem.SystemLine line) {
    if (line.reason() == null) {
      return "no reply sent";
    }
    return "no reply sent: "
        + switch (line.reason()) {
          case ENDED_BEFORE -> "conversation ended (" + line.endReason() + "), lead message stored";
          case ENDED_NOW -> "conversation ended (" + line.endReason() + ")";
          case RESET -> "conversation was reset, lead message stored";
          case REPEAT -> "request already acknowledged (LOOP_SUPPRESSED)";
          case CLOSING_SUPPRESSED -> "closing reply already used or rejected";
          case MODEL_SILENT -> "the agent chose silence after the end";
          case HANDOFF -> "handed to a human (" + line.handoffReason() + ")";
        };
  }

  /** "$0.0119": a model call's cost to the hundredth of a cent, as the price table gives it. */
  public String cost(BigDecimal usd) {
    return "$" + usd.setScale(4, RoundingMode.HALF_UP).toPlainString();
  }

  /** "812 ms" under a second, "2.4 s" from there. */
  public String latency(long ms) {
    return ms < 1000 ? ms + " ms" : String.format(Locale.ENGLISH, "%.1f s", ms / 1000.0);
  }

  /** "6,400 tok". */
  public String tokens(int tokens) {
    return String.format(Locale.ENGLISH, "%,d tok", tokens);
  }

  public String turnStatusClass(TurnStatus status) {
    return switch (status) {
      case OK -> "chip pass";
      case REPAIRED, FALLBACK -> "chip warn";
      case ERROR -> "chip fail";
      case NO_REPLY -> "chip";
    };
  }

  public String turnStatus(TurnStatus status) {
    return switch (status) {
      case OK -> "✓ ok";
      case NO_REPLY -> "∅ no reply";
      case REPAIRED -> "↻ repaired";
      case FALLBACK -> "⚠ fallback";
      case ERROR -> "✕ error";
    };
  }

  /** A guard's verdict as the guard list colours it; INFO is plain. */
  public String outcomeClass(TraceOutcome outcome) {
    return switch (outcome) {
      case PASS -> "outcome pass";
      case BLOCK -> "outcome block";
      case OVERRIDE -> "outcome override";
      case REPAIR -> "outcome repair";
      case INFO -> "outcome";
    };
  }

  public String outcome(TraceOutcome outcome) {
    return switch (outcome) {
      case PASS -> "✓ pass";
      case BLOCK -> "✕ block";
      case OVERRIDE -> "↻ override";
      case REPAIR -> "↻ repair";
      case INFO -> "· info";
    };
  }

  /** The decision call's rounds, or the repair's. */
  public String call(TurnView.ModelRow round) {
    return switch (round.call()) {
      case DECIDE -> "Model call";
      case REPAIR -> "Repair call";
    };
  }

  /** A model round decided, asked for tools, or failed with the code it names. */
  public String roundClass(TurnView.ModelRow round) {
    return switch (round.status()) {
      case DECIDED -> "chip pass";
      case TOOL_CALLS -> "chip ghost";
      case FAILED -> "chip fail";
    };
  }

  public String round(TurnView.ModelRow round) {
    return switch (round.status()) {
      case DECIDED -> "✓ decided";
      case TOOL_CALLS -> "⚙ tool calls";
      case FAILED -> "✕ " + round.failure();
    };
  }

  /** The model's reasoning summary as plain text: its markdown bold markers go. */
  public String reasoning(String summary) {
    return summary.replace("**", "");
  }

  public String toolClass(TurnView.ToolRow tool) {
    return tool.answered() ? "chip pass" : "chip warn";
  }

  /** An answer, or the code of the error the tool or calendar gave. */
  public String tool(TurnView.ToolRow tool) {
    return tool.answered() ? "✓ answered" : "⚠ " + tool.result();
  }

  /** How an availability answer's free starts came down to the slots it offered, in a line. */
  public String filtering(ToolResult.Filtering filtering) {
    return filtering.free()
        + " free in the window; "
        + filtering.outsidePartOfDay()
        + " outside the part of day, "
        + filtering.outsideLeadHours()
        + " outside the lead's calling hours, "
        + filtering.overCap()
        + " over the cap";
  }

  /** "20:24:05" on the clock {@code at} carries, the lead's for a turn. */
  public String time(ZonedDateTime at) {
    return at.format(TIME);
  }

  /** "1 repair", "2 repairs". */
  public String count(long count, String noun) {
    return count + " " + noun + (count == 1 ? "" : "s");
  }

  /** Beside a struck reply: what the lead got instead. */
  public String replyFate(TurnView.ReplyFate fate) {
    return switch (fate) {
      case SENT -> "";
      case REWRITTEN -> "rewritten by the repair call";
      case REPLACED -> "replaced by a fixed template";
      case NOT_SENT -> "not sent";
    };
  }

  /** "26 Jul 10:00" in the advisors' zone, for the list's activity column. */
  public String when(Instant at) {
    return WHEN.format(at.atZone(settings.current().scheduling().advisorZone()));
  }

  /** "v1 (26 Jul 10:00)": a version as the diff pickers offer it. */
  public String versionOption(ConfigurationPage.VersionRow row) {
    return row.id() + " (" + when(row.createdAt()) + ")";
  }

  /**
   * What the editor shows, after the file's name: "v1, created 26 Jul 10:00 by repo, active for new
   * conversations." or, for a refused save's texts, "edited from v1, not saved."
   */
  public String editorSource(ConfigurationPage page) {
    ConfigurationPage.VersionRow row = page.selected();
    if (page.unsaved()) {
      return "edited from " + row.id() + ", not saved.";
    }
    return row.id()
        + ", created "
        + when(row.createdAt())
        + " by "
        + row.createdBy()
        + (row.active() ? ", active for new conversations." : ".");
  }

  /** "Active" on the active version's button, else "Activate v2". */
  public String activateLabel(ConfigurationPage page) {
    return page.selected().active() ? "Active" : "Activate " + page.selected().id();
  }

  /** "11,842": an editor text's length, as the meta line counts it. */
  public String characters(String text) {
    return String.format(Locale.ENGLISH, "%,d", text.length());
  }

  /** An enum name for people: underscores become spaces. */
  public String words(Enum<?> value) {
    return value.name().replace('_', ' ');
  }
}
