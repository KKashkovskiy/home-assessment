package com.enrola.anna.web;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.config.SchedulingProperties;
import com.enrola.anna.conversation.ConversationStatus;
import com.enrola.anna.conversation.Source;
import com.enrola.anna.scheduling.LeadZones;
import java.time.Instant;
import java.time.ZoneId;
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
  private static final List<String> PROVIDERS = List.of("Bupa", "HBF", "Medibank", "nib", "HCF");

  private final ZoneId advisorZone;

  public ViewFormats(SchedulingProperties scheduling) {
    this.advisorZone = scheduling.advisorZone();
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

  /** "26 Jul 10:00" in the advisors' zone, for the list's activity column. */
  public String when(Instant at) {
    return WHEN.format(at.atZone(advisorZone));
  }

  /** An enum name for people: underscores become spaces. */
  public String words(Enum<?> value) {
    return value.name().replace('_', ' ');
  }
}
