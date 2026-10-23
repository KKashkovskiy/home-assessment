package com.enrola.anna.scheduling;

import static com.enrola.anna.scheduling.SchedulingFixtures.MELBOURNE;
import static com.enrola.anna.scheduling.SchedulingFixtures.SUNDAY;
import static com.enrola.anna.scheduling.SchedulingFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.AppClock;
import com.enrola.anna.config.SchedulingProperties.CalendarPreset;
import com.enrola.anna.conversation.ConversationService;
import com.enrola.anna.conversation.Source;
import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.LeadRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * book_call over the real H2 file, so the unique index is the real one, and a fresh fake calendar
 * per test: Lauren's conversation on the clock fixed on Sunday 26 July 2026, with the six Monday
 * midday slots offered.
 */
@SpringBootTest(properties = "OPENAI_API_KEY=")
class BookCallToolTest {

  static final Instant NOON_MONDAY = Instant.parse("2026-07-27T02:00:00Z");
  static final String NOON_LABEL = "Mon 27 Jul 12:00 PM AEST";

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void databaseInTempDir(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("anna"));
  }

  @Autowired BookingRepository bookings;
  @Autowired ConversationService conversations;
  @Autowired LeadRepository leads;
  @Autowired JdbcTemplate jdbc;

  final AppClock clock = SchedulingFixtures.clockAt(SUNDAY);
  final FakeCalendar calendar = new FakeCalendar(clock, SchedulingFixtures.defaults());
  BookCallTool tool;
  Lead lauren;
  ToolContext context;

  @BeforeEach
  void offerMondayMidday() {
    jdbc.update("delete from booking");
    jdbc.update("delete from message");
    jdbc.update("delete from conversation");
    tool = new BookCallTool(bookings, calendar, clock);
    lauren = leads.findById("lauren").orElseThrow();
    context = offeredTo(conversations.start("lauren", Source.API).conversationId(), lauren);
  }

  @Test
  void booksOfferedSlot() {
    ToolResult result = book(tool, Slot.idFor(NOON_MONDAY));

    assertThat(result)
        .isInstanceOfSatisfying(
            ToolResult.Booked.class, booked -> assertThat(booked.label()).isEqualTo(NOON_LABEL));
    Booking row = bookings.findByConversationId(context.conversationId()).orElseThrow();
    assertThat(row.id()).isEqualTo(((ToolResult.Booked) result).bookingId());
    assertThat(row.startUtc()).isEqualTo(NOON_MONDAY);
    assertThat(row.localLabel()).isEqualTo(NOON_LABEL);
    assertThat(row.slotId()).isEqualTo(Slot.idFor(NOON_MONDAY));
    assertThat(row.leadZoneId()).isEqualTo("Australia/Melbourne");
  }

  /** A slot from an earlier offer, or one the model made up, was never in the latest result. */
  @Test
  void unknownSlotIsRejected() {
    assertThat(codeOf(book(tool, "sNOPE"))).isEqualTo("UNKNOWN_SLOT");
    assertThat(bookings.count()).isZero();
  }

  /** A day later the offer is stale: by Monday 12:30 the noon slot has passed. */
  @Test
  void staleSlotAfterClockMove() {
    clock.fixAt(LocalDate.of(2026, 7, 27).atTime(12, 30).atZone(MELBOURNE).toInstant());

    assertThat(codeOf(book(tool, Slot.idFor(NOON_MONDAY)))).isEqualTo("NOT_BOOKED");
    assertThat(bookings.count()).isZero();
  }

  /** The model confirms again rather than apologising, and no second event is booked. */
  @Test
  void repeatReturnsExistingBooking() {
    RecordingCalendar calendar = new RecordingCalendar();
    BookCallTool recorded = new BookCallTool(bookings, calendar, clock);
    ToolResult first = book(recorded, Slot.idFor(NOON_MONDAY));

    ToolResult again = book(recorded, Slot.idFor(NOON_MONDAY.plusSeconds(1800)));

    assertThat(again).isEqualTo(first);
    assertThat(calendar.slots).extracting(Slot::startUtc).containsExactly(NOON_MONDAY);
    assertThat(bookings.count()).isEqualTo(1);
  }

  @Test
  void slotTakenOnceThenBooked() {
    BookCallTool slotTaken =
        new BookCallTool(bookings, calendarWith(CalendarPreset.SLOT_TAKEN), clock);

    assertThat(codeOf(book(slotTaken, Slot.idFor(NOON_MONDAY)))).isEqualTo("SLOT_TAKEN");
    assertThat(bookings.count()).isZero();
    assertThat(codeOf(book(slotTaken, Slot.idFor(NOON_MONDAY)))).isEqualTo("Booked");
    assertThat(bookings.count()).isEqualTo(1);
  }

  /** The arguments carry a slot id and nothing else; who is called comes from the lead row. */
  @Test
  void inviteeFromLeadRowOnly() {
    RecordingCalendar calendar = new RecordingCalendar();

    book(new BookCallTool(bookings, calendar, clock), Slot.idFor(NOON_MONDAY));

    assertThat(calendar.invitees).singleElement().extracting(Lead::phone).isEqualTo("+61400000001");
  }

  @Test
  void noPhoneIsNotBooked() {
    Lead noPhone = new Lead("pat", "Pat", " ", null, "VIC", null, null, null, null, SUNDAY);
    ToolContext withoutPhone =
        new ToolContext(context.conversationId(), noPhone, MELBOURNE, context.lastOffered());

    assertThat(codeOf(tool.run(new BookCallTool.Args(Slot.idFor(NOON_MONDAY)), withoutPhone)))
        .isEqualTo("NO_PHONE");
    assertThat(bookings.count()).isZero();
  }

  /** Another turn saved its booking while this one waited on the calendar: that booking stands. */
  @Test
  void concurrentBookingWins() {
    Booking rival =
        new Booking(
            "cal-rival",
            context.conversationId(),
            Slot.idFor(NOON_MONDAY),
            NOON_MONDAY,
            NOON_LABEL,
            "Australia/Melbourne",
            SUNDAY);
    FakeCalendar racing =
        new FakeCalendar(clock, SchedulingFixtures.defaults()) {
          @Override
          public ToolResult book(Slot slot, Lead invitee) {
            bookings.save(rival);
            return super.book(slot, invitee);
          }
        };

    ToolResult result =
        book(new BookCallTool(bookings, racing, clock), Slot.idFor(NOON_MONDAY.plusSeconds(1800)));

    assertThat(result).isEqualTo(new ToolResult.Booked("cal-rival", NOON_LABEL));
    assertThat(bookings.count()).isEqualTo(1);
  }

  /** The calendar booked, the row did not save: whether the call is booked is now unknown. */
  @Test
  void rowThatFailsAfterTheCalendarBookedIsUnknown() {
    ToolContext missing = new ToolContext("c-missing", lauren, MELBOURNE, context.lastOffered());

    assertThat(tool.run(new BookCallTool.Args(Slot.idFor(NOON_MONDAY)), missing))
        .isInstanceOf(ToolResult.BookingUnknown.class);
    assertThat(bookings.count()).isZero();
  }

  private ToolResult book(BookCallTool bookCall, String slotId) {
    return bookCall.run(new BookCallTool.Args(slotId), context);
  }

  private ToolContext offeredTo(String conversationId, Lead lead) {
    ToolResult offer =
        new AvailabilityTool(clock, SchedulingFixtures.defaults(), calendar)
            .run(
                new AvailabilityTool.Args(
                    "2026-07-27", "2026-07-27", AvailabilityTool.PartOfDay.MIDDAY),
                MELBOURNE);
    List<Slot> slots = ((ToolResult.Slots) offer).slots();
    assertThat(slots).extracting(Slot::label).hasSize(6).contains(NOON_LABEL);
    return new ToolContext(conversationId, lead, MELBOURNE, slots);
  }

  private FakeCalendar calendarWith(CalendarPreset preset) {
    return new FakeCalendar(clock, SchedulingFixtures.props(preset, List.of()));
  }

  /** The shipped calendar, keeping each slot it was asked to book and for whom. */
  private final class RecordingCalendar extends FakeCalendar {

    final List<Slot> slots = new ArrayList<>();
    final List<Lead> invitees = new ArrayList<>();

    RecordingCalendar() {
      super(clock, SchedulingFixtures.defaults());
    }

    @Override
    public ToolResult book(Slot slot, Lead invitee) {
      slots.add(slot);
      invitees.add(invitee);
      return super.book(slot, invitee);
    }
  }
}
