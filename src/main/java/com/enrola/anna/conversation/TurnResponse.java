package com.enrola.anna.conversation;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.scheduling.Booking;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * API response for every conversation call: one shape, snake_case on the wire by the app's Jackson
 * setting. {@code promptVersion} is the version the conversation pinned at start. Fields with
 * nothing to report ({@code booking} before book_call, {@code extracted}) are null, not absent, so
 * the platform parses one contract. {@code lastOfferedSlots} are the labels of the latest
 * availability result, empty before the first. {@code trace_url} is the conversation's page path.
 * Once a turn carried health information, message bodies from that turn on are hidden unless asked
 * for; {@code reply} is always what is to be sent, and {@code noReply} says why a turn sent
 * nothing.
 */
public record TurnResponse(
    String conversationId,
    String promptVersion,
    String reply,
    NoReply noReply,
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
    List<String> lastOfferedSlots,
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

  /** The booked call, as the booking row has it. */
  public record BookingView(String id, Instant startUtc, String localLabel) {

    static BookingView of(Booking booking) {
      return booking == null
          ? null
          : new BookingView(booking.id(), booking.startUtc(), booking.localLabel());
    }
  }

  /** What the call answers: the reply to send, or why there is none; both null for a view. */
  record Answer(String reply, NoReply noReply) {

    static final Answer NONE = new Answer(null, null);

    static Answer sent(String reply) {
      return new Answer(reply, null);
    }
  }

  /** The rows a response is built from: the conversation, its booking or null, its transcript. */
  record Rows(Conversation conversation, Booking booking, List<Message> transcript) {}

  /** "Sun 26 Jul 10:00 AM AEST": the lead's wall clock, readable without arithmetic. */
  private static final DateTimeFormatter LEAD_LOCAL_TIME =
      DateTimeFormatter.ofPattern("EEE d MMM h:mm a z", Locale.ENGLISH);

  /** Bodies from the conversation's HEALTH_INFO turn on are hidden. */
  static TurnResponse of(Rows rows, Answer answer, Instant now) {
    Conversation conversation = rows.conversation();
    return build(
        rows,
        answer,
        rows.transcript().stream()
            .map(
                message ->
                    conversation.hidesBody(message.turnNo())
                        ? MessageView.hidden(message)
                        : MessageView.of(message))
            .toList(),
        now);
  }

  /** Every body shown, health information included; there is no reply. */
  static TurnResponse withHealth(Rows rows, Instant now) {
    return build(rows, Answer.NONE, rows.transcript().stream().map(MessageView::of).toList(), now);
  }

  private static TurnResponse build(
      Rows rows, Answer answer, List<MessageView> transcript, Instant now) {
    Conversation conversation = rows.conversation();
    return new TurnResponse(
        conversation.id,
        conversation.promptVersion,
        answer.reply(),
        answer.noReply(),
        conversation.stage,
        conversation.goalMet,
        conversation.unsubscribed,
        conversation.ended,
        conversation.endReason,
        conversation.needsHuman,
        conversation.handoffReason,
        List.copyOf(conversation.flags()),
        conversation.endReasonHistory(),
        BookingView.of(rows.booking()),
        conversation.lastOfferedLabels(),
        conversation.extracted(),
        now.atZone(ZoneId.of(conversation.leadZoneId)).format(LEAD_LOCAL_TIME),
        "/conversations/" + conversation.id,
        transcript);
  }
}
