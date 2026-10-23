package com.enrola.anna.scheduling;

import com.enrola.anna.AppClock;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * book_call: only a slot from the conversation's latest offer, re-checked by the calendar because
 * an offer goes stale between turns; the invitee from the stored lead, never from the model; one
 * booking per conversation, so a repeat call returns the booking already made. A refusal goes back
 * to the model, which re-queries; a booking the calendar made but the database did not keep is
 * {@link ToolResult.BookingUnknown}.
 */
@Component
public class BookCallTool {

  private static final ToolResult.Rejected UNKNOWN_SLOT =
      new ToolResult.Rejected(
          "UNKNOWN_SLOT", "slot_id must come from the latest get_availability result");
  private static final ToolResult.Rejected NO_PHONE =
      new ToolResult.Rejected("NO_PHONE", "no phone on file; the advisor team will follow up");
  private static final ToolResult.BookingUnknown UNKNOWN_BOOKING =
      new ToolResult.BookingUnknown(
          "do not retry; tell the lead the advisor team will confirm a time");

  private final BookingRepository bookings;
  private final FakeCalendar calendar;
  private final AppClock clock;

  public BookCallTool(BookingRepository bookings, FakeCalendar calendar, AppClock clock) {
    this.bookings = bookings;
    this.calendar = calendar;
    this.clock = clock;
  }

  /** The model's one argument, as {@code tools.json} declares it; nothing else can be passed. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Args(String slotId) {

    public Args {
      Objects.requireNonNull(slotId, "slot_id");
    }
  }

  /** Books the offered slot {@code args} names for the conversation {@code context} is about. */
  public ToolResult run(Args args, ToolContext context) {
    Optional<Booking> existing = bookings.findByConversationId(context.conversationId());
    if (existing.isPresent()) {
      return booked(existing.get());
    }
    Optional<Slot> slot =
        context.lastOffered().stream()
            .filter(offered -> offered.slotId().equals(args.slotId()))
            .findFirst();
    if (slot.isEmpty()) {
      return UNKNOWN_SLOT;
    }
    String phone = context.lead().phone();
    if (phone == null || phone.isBlank()) {
      return NO_PHONE;
    }
    return book(slot.get(), context);
  }

  /** The calendar first, then the row; a refusal from the calendar goes back as it came. */
  private ToolResult book(Slot slot, ToolContext context) {
    ToolResult answer = calendar.book(slot, context.lead());
    if (!(answer instanceof ToolResult.Booked event)) {
      return answer;
    }
    try {
      bookings.save(
          new Booking(
              event.bookingId(),
              context.conversationId(),
              slot.slotId(),
              slot.startUtc(),
              slot.label(),
              context.leadZone().getId(),
              clock.now()));
      return event;
    } catch (DuplicateKeyException _) { // a concurrent turn booked first, and its booking stands
      return bookings
          .findByConversationId(context.conversationId())
          .<ToolResult>map(BookCallTool::booked)
          .orElse(UNKNOWN_BOOKING);
    } catch (DataAccessException _) { // the calendar holds an event no row records
      return UNKNOWN_BOOKING;
    }
  }

  private static ToolResult.Booked booked(Booking booking) {
    return new ToolResult.Booked(booking.id(), booking.localLabel());
  }
}
