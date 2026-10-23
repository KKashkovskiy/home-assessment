package com.enrola.anna.conversation;

import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.InputItem;
import com.enrola.anna.agent.TurnContext;
import com.enrola.anna.config.Settings;
import com.enrola.anna.scheduling.Booking;
import com.enrola.anna.scheduling.BookingRepository;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * What the model is asked on a running turn: the transcript replayed from the rows and the turn's
 * context on the lead's clock, with the counters, the latest offer and the booking, and for a
 * repair the same again with a note.
 */
@Component
class TurnQuestions {

  private final MessageRepository messages;
  private final BookingRepository bookings;

  TurnQuestions(MessageRepository messages, BookingRepository bookings) {
    this.messages = messages;
    this.bookings = bookings;
  }

  /** The transcript and the turn's context, as of the turn's start. */
  record Question(List<Message> transcript, TurnContext context) {}

  /** {@code text} cut to its first {@code maxChars} characters when it is longer. */
  static String cut(String text, int maxChars) {
    return text.length() > maxChars ? text.substring(0, maxChars) : text;
  }

  Question questionFor(RunningTurn turn) {
    return new Question(messages.transcript(turn.conversation().id), contextFor(turn));
  }

  /**
   * {@code asked} again with a note on what the rejected reply broke, and the offer and the booking
   * as they stand now: this turn's tool calls may have changed both since the question started.
   */
  Question repairOf(RunningTurn turn, Question asked, String note) {
    Conversation conversation = turn.conversation();
    return new Question(
        asked.transcript(),
        asked
            .context()
            .withRepair(note, conversation.lastOfferedSlots(), bookingLabel(conversation)));
  }

  /**
   * Anna's messages, the opener included, as assistant items and the lead's as user items, each cut
   * to the inbound limit: the model sees what the lead saw and said.
   */
  List<InputItem> transcriptOf(Question question, Settings.Limits limits) {
    return question.transcript().stream()
        .<InputItem>map(
            message ->
                switch (message.direction()) {
                  case OUT -> new InputItem.AssistantText(message.text());
                  case IN -> new InputItem.UserText(cut(message.text(), limits.maxInboundChars()));
                })
        .toList();
  }

  /** The booked call's label, the one booked time a reply may name; null without a booking. */
  String bookingLabel(Conversation conversation) {
    return bookings.findByConversationId(conversation.id).map(Booking::localLabel).orElse(null);
  }

  /** The lead's clock is in the zone fixed at start. */
  private TurnContext contextFor(RunningTurn turn) {
    Conversation conversation = turn.conversation();
    return new TurnContext(
        turn.lead(),
        turn.now().atZone(ZoneId.of(conversation.leadZoneId)),
        conversation.flags().contains(Flag.TZ_ASSUMED),
        new TurnContext.Counters(
            conversation.agentTurns,
            conversation.objectionsUsed,
            conversation.offTopicStreak,
            conversation.postTerminalReplies),
        turn.settings().limits(),
        bookingLabel(conversation),
        conversation.lastOfferedSlots(),
        conversation.ended,
        conversation.endReason,
        null);
  }
}
