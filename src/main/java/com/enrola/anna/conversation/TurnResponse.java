package com.enrola.anna.conversation;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.Stage;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * API response for every conversation call: one shape, snake_case on the wire by the app's Jackson
 * setting. Fields with nothing to report ({@code booking}, {@code extracted}) are null, not absent,
 * so the platform parses one contract. {@code trace_url} is the conversation's page path. Once a
 * turn carried health information, message bodies from that turn on are hidden unless asked for;
 * {@code reply} is always what is to be sent.
 */
public record TurnResponse(
    String conversationId,
    String reply,
    Stage stage,
    boolean goalMet,
    boolean unsubscribed,
    boolean conversationEnded,
    EndReason endReason,
    boolean needsHuman,
    HandoffReason handoffReason,
    List<Flag> flags,
    List<EndReasonEntry> endReasonHistory,
    BookingView booking,
    JsonNode extracted,
    String leadLocalTime,
    String traceUrl,
    List<MessageView> transcript) {

  /**
   * One transcript line. A hidden body has {@code text} null and {@code redacted} naming the flag
   * that hid it; {@code redacted} is absent when the body is shown.
   */
  public record MessageView(
      int turn,
      Direction direction,
      String text,
      int chars,
      MessageSource source,
      Instant at,
      @JsonInclude(JsonInclude.Include.NON_NULL) String redacted) {

    static MessageView of(Message message) {
      return new MessageView(
          message.turnNo(),
          message.direction(),
          message.text(),
          message.charCount(),
          message.source(),
          message.createdAt(),
          null);
    }

    static MessageView hidden(Message message) {
      return new MessageView(
          message.turnNo(),
          message.direction(),
          null,
          message.charCount(),
          message.source(),
          message.createdAt(),
          Flag.HEALTH_INFO.name());
    }
  }

  /** The booked call. */
  public record BookingView(String id, Instant startUtc, String localLabel) {}

  /** "Sun 26 Jul 10:00 AM AEST": the lead's wall clock, readable without arithmetic. */
  private static final DateTimeFormatter LEAD_LOCAL_TIME =
      DateTimeFormatter.ofPattern("EEE d MMM h:mm a z", Locale.ENGLISH);

  /** Bodies from the conversation's HEALTH_INFO turn on are hidden. */
  static TurnResponse of(
      Conversation conversation, String reply, List<Message> transcript, Instant now) {
    Integer hiddenFrom = conversation.healthInfoTurn;
    return build(
        conversation,
        reply,
        transcript.stream()
            .map(
                message ->
                    hiddenFrom != null && message.turnNo() >= hiddenFrom
                        ? MessageView.hidden(message)
                        : MessageView.of(message))
            .toList(),
        now);
  }

  /** Every body shown, health information included; there is no reply. */
  static TurnResponse withHealth(Conversation conversation, List<Message> transcript, Instant now) {
    return build(conversation, null, transcript.stream().map(MessageView::of).toList(), now);
  }

  private static TurnResponse build(
      Conversation conversation, String reply, List<MessageView> transcript, Instant now) {
    return new TurnResponse(
        conversation.id,
        reply,
        conversation.stage,
        conversation.goalMet,
        conversation.unsubscribed,
        conversation.ended,
        conversation.endReason,
        conversation.needsHuman,
        conversation.handoffReason,
        List.copyOf(conversation.flags()),
        conversation.endReasonHistory(),
        null,
        conversation.extracted(),
        now.atZone(ZoneId.of(conversation.leadZoneId)).format(LEAD_LOCAL_TIME),
        "/conversations/" + conversation.id,
        transcript);
  }
}
