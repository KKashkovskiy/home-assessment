package com.enrola.anna.eval;

import com.enrola.anna.AppClock;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsStore;
import com.enrola.anna.conversation.ConflictException;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.conversation.ConversationRepository;
import com.enrola.anna.conversation.ConversationService;
import com.enrola.anna.conversation.Message;
import com.enrola.anna.conversation.MessageRepository;
import com.enrola.anna.conversation.MessageSource;
import com.enrola.anna.conversation.SeededState;
import com.enrola.anna.conversation.Source;
import com.enrola.anna.eval.Scenario.History;
import com.enrola.anna.eval.Scenario.Seed;
import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.LeadRepository;
import com.enrola.anna.scheduling.Booking;
import com.enrola.anna.scheduling.BookingRepository;
import com.enrola.anna.scheduling.FakeCalendar;
import com.enrola.anna.scheduling.LeadZones;
import com.enrola.anna.scheduling.Slot;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Writes a turn scenario's starting state as the rows the product would have left, after fixing the
 * clock and the calendar preset. The conversation starts through {@link ConversationService}, so
 * the opener, the prompt pin and the lead's zone are the product's; the history becomes message
 * rows with turn numbers, offered labels become real slots, and the seed's booking and end go
 * through the product's state rules, as a turn would set them.
 *
 * <p>Each seed gets a lead row of its own: id the scenario id, with {@code -2}, {@code -3} and on
 * for a repeat in the same database, and a copy of the named seeded lead, or of the inline record,
 * on a phone derived from that id; only the first seed of an inline lead keeps its own phone. A
 * STOP in one trial then never suppresses the next, and a scenario can run twice.
 */
final class Seeder {

  private final ConversationService conversations;
  private final ConversationRepository rows;
  private final LeadRepository leads;
  private final MessageRepository messages;
  private final BookingRepository bookings;
  private final AppClock clock;
  private final FakeCalendar calendar;
  private final SettingsStore settings;

  Seeder(
      ConversationService conversations,
      ConversationRepository rows,
      LeadRepository leads,
      MessageRepository messages,
      BookingRepository bookings,
      AppClock clock,
      FakeCalendar calendar,
      SettingsStore settings) {
    this.conversations = conversations;
    this.rows = rows;
    this.leads = leads;
    this.messages = messages;
    this.bookings = bookings;
    this.clock = clock;
    this.calendar = calendar;
    this.settings = settings;
  }

  /**
   * The seeded conversation, as saved. Every label resolves before any row is written, so one the
   * calendar cannot produce is a {@link ScenarioException} with nothing to clean up; a start the
   * product refuses is its {@link ConflictException}.
   */
  Conversation seed(Scenario.Turn scenario) {
    clock.fixAt(scenario.clock().toInstant());
    calendar.preset(scenario.calendar());
    Lead template = template(scenario);
    Map<String, Slot> slots = resolveLabels(scenario, template);
    Lead lead = leadFor(scenario, template);
    Conversation conversation =
        rows.require(conversations.start(lead.id(), Source.EVAL).conversationId());
    Instant now = clock.now();
    writeHistory(conversation, scenario.history(), slots, now);
    applySeed(conversation, scenario.seed(), slots, now);
    return rows.save(conversation);
  }

  /** The lead the scenario names, unsaved: the seeded row, or the inline record. */
  private Lead template(Scenario.Turn scenario) {
    if (scenario.lead().inline() != null) {
      return scenario.lead().inline();
    }
    return leads
        .findById(scenario.lead().name())
        .orElseThrow(() -> new ScenarioException("no seeded lead " + scenario.lead().name()));
  }

  /** A new lead row for this seed, a copy of {@code template}. */
  private Lead leadFor(Scenario.Turn scenario, Lead template) {
    String id = freeLeadId(scenario.id());
    boolean ownPhone = scenario.lead().inline() != null && id.equals(scenario.id());
    return leads.save(
        new Lead(
            id,
            template.givenName(),
            ownPhone ? template.phone() : phoneFor(id),
            template.email(),
            template.state(),
            template.currentProvider(),
            template.currentPremium(),
            null,
            null,
            clock.now()));
  }

  /** The scenario id, or with the first free suffix {@code -2}, {@code -3}... */
  private String freeLeadId(String scenarioId) {
    String id = scenarioId;
    for (int repeat = 2; leads.existsById(id); repeat++) {
      id = scenarioId + "-" + repeat;
    }
    return id;
  }

  /** A mobile number of the lead's own, stable for its id. */
  private static String phoneFor(String leadId) {
    return "+6149%07d".formatted(Math.floorMod(leadId.hashCode(), 10_000_000));
  }

  /** Every offered and booked label in the scenario, keyed by label, as the lead's zone has it. */
  private Map<String, Slot> resolveLabels(Scenario.Turn scenario, Lead lead) {
    Settings.Scheduling scheduling = settings.current().scheduling();
    ZoneId zone = LeadZones.resolve(lead.state(), scheduling.advisorZone()).zone();
    List<String> labels =
        Stream.concat(
                scenario.history().stream()
                    .filter(line -> line.offeredSlots() != null)
                    .flatMap(line -> line.offeredSlots().stream()),
                Stream.ofNullable(scenario.seed().booking()))
            .toList();
    Map<String, Slot> byLabel = new HashMap<>();
    try {
      calendar
          .slotsForLabels(labels, zone, scheduling)
          .forEach(slot -> byLabel.put(slot.label(), slot));
    } catch (IllegalArgumentException e) {
      throw new ScenarioException(scenario.id() + ": " + e.getMessage());
    }
    return byLabel;
  }

  /**
   * History lines as message rows. The first line is the opener, and when its text differs from the
   * rendered template the scenario's replaces it, since that is the transcript the model must see.
   * Lead lines claim turns 1, 2 and on; an agent line answers the turn before it, and the last one
   * to offer slots is the latest offer, as in the product.
   */
  private void writeHistory(
      Conversation conversation, List<History> history, Map<String, Slot> slots, Instant now) {
    int turn = 0;
    for (History line : history) {
      if (line.lead() != null) {
        turn++;
        messages.save(Message.inbound(conversation.id, turn, line.lead(), "seed-" + turn, now));
      } else if (turn == 0) {
        replaceOpener(conversation, line.agent(), now);
      } else {
        messages.save(
            Message.outbound(conversation.id, turn, line.agent(), MessageSource.LLM, now));
        conversation.agentTurns++;
      }
      if (line.offeredSlots() != null) {
        conversation.setLastOfferedSlots(line.offeredSlots().stream().map(slots::get).toList());
      }
    }
  }

  private void replaceOpener(Conversation conversation, String text, Instant now) {
    Message opener = messages.transcript(conversation.id).getFirst();
    if (!opener.text().equals(text)) {
      messages.delete(opener);
      messages.save(Message.outbound(conversation.id, 0, text, MessageSource.TEMPLATE, now));
    }
  }

  private void applySeed(
      Conversation conversation, Seed seed, Map<String, Slot> slots, Instant now) {
    conversation.objectionsUsed = seed.objectionsUsed();
    conversation.offTopicStreak = seed.offTopicStreak();
    conversation.postTerminalReplies = seed.closingRepliesSent();
    if (seed.booking() != null) {
      bookings.save(booking(conversation, slots.get(seed.booking()), now));
      SeededState.booked(conversation, now);
    }
    if (seed.ended() != null && seed.ended() != EndReason.BOOKED) {
      SeededState.end(conversation, seed.ended(), now);
    }
  }

  private static Booking booking(Conversation conversation, Slot slot, Instant now) {
    return new Booking(
        "cal-" + UUID.randomUUID(),
        conversation.id,
        slot.slotId(),
        slot.startUtc(),
        slot.label(),
        conversation.leadZoneId,
        now);
  }
}
