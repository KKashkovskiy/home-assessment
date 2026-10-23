package com.enrola.anna.web;

import com.enrola.anna.AppClock;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.agent.ToolCall;
import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsStore;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.conversation.ConversationRepository;
import com.enrola.anna.conversation.Direction;
import com.enrola.anna.conversation.Message;
import com.enrola.anna.conversation.MessageRepository;
import com.enrola.anna.conversation.Turn;
import com.enrola.anna.conversation.TurnRepository;
import com.enrola.anna.conversation.TurnResponse;
import com.enrola.anna.conversation.TurnStatus;
import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.LeadRepository;
import com.enrola.anna.scheduling.Booking;
import com.enrola.anna.scheduling.BookingRepository;
import com.enrola.anna.scheduling.LeadZones;
import com.enrola.anna.scheduling.SchedulingTools;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds one conversation's {@link ConsoleView} from its rows: the thread, the state header and the
 * booking card. Masking happens here, before the model, so with the mask on a template never holds
 * a raw phone number, email or health detail.
 */
@Component
public class ConsoleViews {

  private static final TypeReference<List<ToolCall>> TOOL_CALLS = new TypeReference<>() {};
  private static final DateTimeFormatter WALL_CLOCK =
      DateTimeFormatter.ofPattern("EEE d MMM h:mm a", Locale.ENGLISH);
  private static final DateTimeFormatter BUBBLE_TIME = DateTimeFormatter.ofPattern("HH:mm");

  private final ConversationRepository conversations;
  private final LeadRepository leads;
  private final MessageRepository messages;
  private final TurnRepository turns;
  private final BookingRepository bookings;
  private final SettingsStore settings;
  private final AppClock clock;
  private final JsonMapper json;

  /**
   * How a conversation reaches the page: free text as stored with the mask off; with it on, phones
   * and emails masked, and hidden whole where the conversation carries health information. Lengths
   * are measured against the limits this render read, so a reply sent under an older limit shows
   * against today's.
   */
  private record Shown(Conversation conversation, boolean mask, Settings.Limits limits) {

    String freeText(String text) {
      return mask ? PiiMask.freeText(text, conversation.healthInfoTurn != null) : text;
    }

    /** A message is hidden only from the turn that first carried health information on. */
    String body(Message message) {
      return mask
          ? PiiMask.freeText(message.text(), conversation.hidesBody(message.turnNo()))
          : message.text();
    }
  }

  public ConsoleViews(
      ConversationRepository conversations,
      LeadRepository leads,
      MessageRepository messages,
      TurnRepository turns,
      BookingRepository bookings,
      SettingsStore settings,
      AppClock clock,
      JsonMapper json) {
    this.conversations = conversations;
    this.leads = leads;
    this.messages = messages;
    this.turns = turns;
    this.bookings = bookings;
    this.settings = settings;
    this.clock = clock;
    this.json = json;
  }

  /** One conversation for the chat and state panes; {@code mask} hides phone, email and health. */
  public ConsoleView build(String conversationId, boolean mask) {
    Conversation conversation = conversations.require(conversationId);
    Lead lead = leadOf(conversation);
    List<Turn> turnRows = turns.findByConversationIdOrderByTurnNoAsc(conversationId);
    Settings current = settings.current();
    Shown shown = new Shown(conversation, mask, current.limits());
    return new ConsoleView(
        conversation,
        mask ? PiiMask.apply(lead) : lead,
        wallClock(clock.now(), ZoneId.of(conversation.leadZoneId)),
        thread(conversation, turnRows, shown),
        seen(turnRows),
        counters(conversation, shown.limits()),
        shown.freeText(conversation.summary),
        facts(conversation.extracted(), shown),
        bookings
            .findByConversationId(conversationId)
            .map(booking -> bookingCard(booking, current.scheduling()))
            .orElse(null),
        shown.limits().maxInboundChars());
  }

  /**
   * Each counter against its limit, at the limit when the next event ends the conversation or is
   * refused: the last reply before the turn cap, the objection with no retry left, the off-topic
   * message that ends the streak, the closing reply already used.
   */
  static List<ConsoleView.Counter> counters(Conversation conversation, Settings.Limits limits) {
    return List.of(
        new ConsoleView.Counter(
            "turns",
            conversation.agentTurns,
            limits.maxTurns(),
            conversation.agentTurns >= limits.maxTurns() - 1),
        new ConsoleView.Counter(
            "objections",
            conversation.objectionsUsed,
            limits.objectionRetries(),
            conversation.objectionsUsed >= limits.objectionRetries()),
        new ConsoleView.Counter(
            "off-topic streak",
            conversation.offTopicStreak,
            limits.offTopicLimit(),
            conversation.offTopicStreak >= limits.offTopicLimit() - 1),
        new ConsoleView.Counter(
            "closing replies",
            conversation.postTerminalReplies,
            1,
            conversation.postTerminalReplies >= 1));
  }

  private Lead leadOf(Conversation conversation) {
    return leads
        .findById(conversation.leadId)
        .orElseThrow(() -> new IllegalStateException("lead missing for " + conversation.id));
  }

  /** "Sun 26 Jul 10:00 AM AEST": a wall clock in {@code zone}, named the way leads read it. */
  private static String wallClock(Instant at, ZoneId zone) {
    return at.atZone(zone).format(WALL_CLOCK) + " " + LeadZones.abbreviation(zone, at);
  }

  /** Each turn's lead bubble, its tool chips, then the agent bubble or a line saying none went. */
  private List<ThreadItem> thread(Conversation conversation, List<Turn> turnRows, Shown shown) {
    ZoneId zone = ZoneId.of(conversation.leadZoneId);
    Map<Integer, TurnStatus> statuses =
        turnRows.stream().collect(Collectors.toMap(Turn::turnNo, Turn::status));
    List<ThreadItem> items = new ArrayList<>();
    for (Message message : messages.transcript(conversation.id)) {
      items.add(bubble(message, source(message, statuses), zone, shown));
    }
    Set<Integer> answered =
        items.stream()
            .filter(item -> item instanceof ThreadItem.Bubble bubble && !bubble.lead())
            .map(ThreadItem::turnNo)
            .collect(Collectors.toSet());
    for (Turn turn : turnRows) {
      items.addAll(toolChips(turn));
      if (!answered.contains(turn.turnNo())) {
        items.add(systemLine(turn));
      }
    }
    items.sort(Comparator.comparingInt(ThreadItem::turnNo).thenComparingInt(ThreadItem::order));
    return items;
  }

  private static ThreadItem.Bubble bubble(
      Message message, ReplySource source, ZoneId zone, Shown shown) {
    Settings.Limits limits = shown.limits();
    return new ThreadItem.Bubble(
        message.direction(),
        shown.body(message),
        message.charCount(),
        limits.maxChars(),
        message.charCount() > limits.targetChars(),
        source,
        message.repaired(),
        message.turnNo(),
        message.createdAt().atZone(zone).format(BUBBLE_TIME));
  }

  private static ReplySource source(Message message, Map<Integer, TurnStatus> statuses) {
    return message.direction() == Direction.IN
        ? null
        : ReplySource.of(message.source(), statuses.get(message.turnNo()));
  }

  private List<ThreadItem.ToolChip> toolChips(Turn turn) {
    String callsJson = turn.modelCall().toolCallsJson();
    if (callsJson == null) {
      return List.of();
    }
    return json.readValue(callsJson, TOOL_CALLS).stream()
        .map(
            call ->
                new ThreadItem.ToolChip(
                    turn.turnNo(), call.name(), SchedulingTools.summary(call.resultJson())))
        .toList();
  }

  /** Why the turn sent nothing, as stored with its response, and the state it left. */
  private ThreadItem.SystemLine systemLine(Turn turn) {
    TurnResponse response = json.readValue(turn.responseJson(), TurnResponse.class);
    return new ThreadItem.SystemLine(
        turn.turnNo(), response.noReply(), response.endReason(), response.handoffReason());
  }

  /** The stages the model's decisions named, so the tracker can mark the ones it jumped. */
  private Set<Stage> seen(List<Turn> turnRows) {
    Set<Stage> seen = EnumSet.noneOf(Stage.class);
    turnRows.stream()
        .map(turn -> turn.modelCall().decisionJson())
        .filter(Objects::nonNull)
        .map(decisionJson -> text(json.readTree(decisionJson), "stage"))
        .forEach(named -> stageNamed(named).ifPresent(seen::add));
    return seen;
  }

  /** A decision that failed the schema may name no stage, or one that is not a stage. */
  private static Optional<Stage> stageNamed(String name) {
    return Arrays.stream(Stage.values()).filter(stage -> stage.name().equals(name)).findFirst();
  }

  /** The extracted facts; the one free-text fact, the cover note, is shown like the summary. */
  private static ConsoleView.Facts facts(JsonNode extracted, Shown shown) {
    if (extracted == null) {
      return null;
    }
    return new ConsoleView.Facts(
        text(extracted, "coverage_goal"),
        text(extracted, "cover_type"),
        shown.freeText(text(extracted, "current_cover_note")),
        text(extracted, "preferred_day"),
        text(extracted, "preferred_time_of_day"));
  }

  private static ConsoleView.BookingCard bookingCard(
      Booking booking, Settings.Scheduling scheduling) {
    return new ConsoleView.BookingCard(
        booking.id(),
        booking.localLabel(),
        wallClock(booking.startUtc(), scheduling.advisorZone()),
        booking.startUtc().toString(),
        scheduling.eventMin());
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isString() ? value.stringValue() : null;
  }
}
