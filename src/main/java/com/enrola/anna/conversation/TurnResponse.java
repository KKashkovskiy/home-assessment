package com.enrola.anna.conversation;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * API response for every conversation call: one shape, snake_case on the wire by the app's Jackson
 * setting. Fields with nothing to report ({@code booking}, {@code extracted}) are null, not absent,
 * so the platform parses one contract. {@code trace_url} is the conversation's page path.
 */
public record TurnResponse(
    String conversationId,
    String reply,
    Stage stage,
    boolean goalMet,
    boolean unsubscribed,
    boolean conversationEnded,
    String endReason,
    boolean needsHuman,
    String handoffReason,
    List<Flag> flags,
    List<EndReasonEntry> endReasonHistory,
    BookingView booking,
    JsonNode extracted,
    String leadLocalTime,
    String traceUrl,
    List<MessageView> transcript) {

  /** One transcript line. */
  public record MessageView(
      int turn, Direction direction, String text, int chars, MessageSource source, Instant at) {

    static MessageView of(Message message) {
      return new MessageView(
          message.turnNo(),
          message.direction(),
          message.text(),
          message.charCount(),
          message.source(),
          message.createdAt());
    }
  }

  /** The booked call. */
  public record BookingView(String id, Instant startUtc, String localLabel) {}

  /** One terminal-state transition. */
  public record EndReasonEntry(String reason, Instant at) {}

  /** "Sun 26 Jul 10:00 AM AEST": the lead's wall clock, readable without arithmetic. */
  private static final DateTimeFormatter LEAD_LOCAL_TIME =
      DateTimeFormatter.ofPattern("EEE d MMM h:mm a z", Locale.ENGLISH);

  static TurnResponse of(
      Conversation conversation, String reply, List<Message> transcript, Instant now) {
    return new TurnResponse(
        conversation.id,
        reply,
        conversation.stage,
        conversation.goalMet,
        conversation.unsubscribed,
        conversation.ended,
        "NONE",
        conversation.needsHuman,
        "NONE",
        List.copyOf(conversation.flags()),
        List.of(),
        null,
        null,
        now.atZone(ZoneId.of(conversation.leadZoneId)).format(LEAD_LOCAL_TIME),
        "/conversations/" + conversation.id,
        transcript.stream().map(MessageView::of).toList());
  }
}
