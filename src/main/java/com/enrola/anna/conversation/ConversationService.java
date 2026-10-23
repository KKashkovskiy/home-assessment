package com.enrola.anna.conversation;

import com.enrola.anna.AppClock;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsStore;
import com.enrola.anna.guard.Suppressions;
import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.LeadRepository;
import com.enrola.anna.prompt.PromptBundle;
import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.Template;
import com.enrola.anna.scheduling.BookingRepository;
import com.enrola.anna.scheduling.LeadZones;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts conversations, resets them from the console and reads them back; {@link InboundTurns} runs
 * their turns. Throws {@link NotFoundException} and {@link ConflictException}; status codes are the
 * caller's business.
 *
 * <p>A start holds the lead's {@link TurnLocks} lock and commits inside it, so the next start for
 * that lead sees the new row; that is why it uses a {@link TransactionTemplate} and not
 * {@code @Transactional}.
 */
@Service
public class ConversationService {

  private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

  private final LeadRepository leads;
  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final BookingRepository bookings;
  private final PromptStore prompts;
  private final Suppressions suppressions;
  private final TurnLocks locks;
  private final TransactionTemplate tx;
  private final AppClock clock;
  private final SettingsStore settings;
  private final String appVersion;

  public ConversationService(
      LeadRepository leads,
      ConversationRepository conversations,
      MessageRepository messages,
      BookingRepository bookings,
      PromptStore prompts,
      Suppressions suppressions,
      TurnLocks locks,
      TransactionTemplate tx,
      AppClock clock,
      SettingsStore settings,
      @Value("${anna.app-version}") String appVersion) {
    this.leads = leads;
    this.conversations = conversations;
    this.messages = messages;
    this.bookings = bookings;
    this.prompts = prompts;
    this.suppressions = suppressions;
    this.locks = locks;
    this.tx = tx;
    this.clock = clock;
    this.settings = settings;
    this.appVersion = appVersion;
  }

  /**
   * Creates the conversation and stores the template opener as turn 0. An opted-out phone gets a
   * SUPPRESSED conflict; a lead with an open conversation gets an ACTIVE conflict naming it, and
   * one whose last conversation ended ABUSIVE an ABUSIVE conflict naming that, so only a deliberate
   * {@link #forceStart} texts them again. {@code source} names the client that asked.
   */
  public TurnResponse start(String leadId, Source source) {
    Lead lead = requireLead(leadId);
    return locks.forLead(leadId, () -> tx.execute(_ -> openIfNoneOpen(lead, source)));
  }

  /**
   * The platform's override: like {@link #start}, but opens a second conversation beside an open
   * one, or after an abusive end. An opted-out phone is still refused: nothing overrides an
   * opt-out.
   */
  public TurnResponse forceStart(String leadId) {
    Lead lead = requireLead(leadId);
    return locks.forLead(leadId, () -> tx.execute(_ -> openUnlessSuppressed(lead)));
  }

  /**
   * Console reset: a new conversation for the same lead, the old one kept for history. An open old
   * conversation is closed as RESET; an ended one keeps its reason and booking. Nothing is deleted,
   * and the new start runs every check {@link #start} does, so a reset never bypasses an opt-out.
   * Both locks are held and one transaction covers both rows, so a refused start leaves the old
   * conversation as it was and a turn in flight finishes first or the reset gets BUSY.
   */
  public TurnResponse reset(String conversationId) {
    Lead lead = requireLead(conversations.require(conversationId).leadId);
    return locks.forConversation(
        conversationId,
        () -> locks.forLead(lead.id(), () -> tx.execute(_ -> resetAndOpen(conversationId, lead))));
  }

  /**
   * The conversation as it stands: state, booking and transcript, with no reply. Bodies from a turn
   * that carried health information on are hidden.
   */
  @Transactional(readOnly = true)
  public TurnResponse view(String conversationId) {
    return TurnResponse.of(rowsOf(conversationId), TurnResponse.Answer.NONE, clock.now());
  }

  /** Like {@link #view}, with every body shown, health information included. */
  @Transactional(readOnly = true)
  public TurnResponse viewWithHealth(String conversationId) {
    return TurnResponse.withHealth(rowsOf(conversationId), clock.now());
  }

  private TurnResponse.Rows rowsOf(String conversationId) {
    return new TurnResponse.Rows(
        conversations.require(conversationId),
        bookings.findByConversationId(conversationId).orElse(null),
        messages.transcript(conversationId));
  }

  private Lead requireLead(String leadId) {
    return leads.findById(leadId).orElseThrow(() -> new NotFoundException("lead", leadId));
  }

  private TurnResponse openIfNoneOpen(Lead lead, Source source) {
    rejectIfSuppressed(lead);
    rejectIfOpen(lead);
    rejectIfLastEndedAbusive(lead);
    return open(lead, source);
  }

  private TurnResponse openUnlessSuppressed(Lead lead) {
    rejectIfSuppressed(lead);
    return open(lead, Source.API);
  }

  private TurnResponse resetAndOpen(String conversationId, Lead lead) {
    Conversation old = conversations.require(conversationId);
    if (!old.ended) {
      StateRules.reset(old);
      old.updatedAt = clock.now();
      conversations.save(old);
    }
    return openIfNoneOpen(lead, Source.SIM);
  }

  private void rejectIfSuppressed(Lead lead) {
    if (suppressions.optedOut(lead.phone())) {
      throw new ConflictException("SUPPRESSED", "phone is opted out", null);
    }
  }

  private void rejectIfOpen(Lead lead) {
    conversations
        .findOpenByLead(lead.id())
        .ifPresent(
            open -> {
              throw new ConflictException(
                  "ACTIVE", "lead " + lead.id() + " has an active conversation", open.id);
            });
  }

  private void rejectIfLastEndedAbusive(Lead lead) {
    conversations
        .findLatestByLead(lead.id())
        .filter(latest -> latest.endReason == EndReason.ABUSIVE)
        .ifPresent(
            abusive -> {
              throw new ConflictException(
                  "ABUSIVE",
                  "lead " + lead.id() + "'s last conversation ended abusive",
                  abusive.id);
            });
  }

  /**
   * The only place the active prompt version is read: the conversation pins it for good. The opener
   * speaks as the current settings' persona.
   */
  private TurnResponse open(Lead lead, Source source) {
    Instant now = clock.now();
    Settings current = settings.current();
    PromptBundle bundle = prompts.bundle(prompts.activeId(), current.persona());
    Conversation conversation =
        conversations.save(newConversation(lead, new Start(bundle, current, source, now)));
    Message opener = storeOpener(conversation, lead, bundle, now);
    return TurnResponse.of(
        new TurnResponse.Rows(conversation, null, List.of(opener)),
        TurnResponse.Answer.sent(opener.text()),
        now);
  }

  /** What a new conversation starts on: the active version, the settings, the client, the time. */
  private record Start(PromptBundle bundle, Settings settings, Source source, Instant now) {}

  /**
   * An unguessable id, because it reaches URLs; version 0 makes {@code save} insert. It records the
   * prompt version, model, effort and build it starts on.
   */
  private Conversation newConversation(Lead lead, Start start) {
    Settings current = start.settings();
    Instant now = start.now();
    LeadZones.Resolved zone = LeadZones.resolve(lead.state(), current.scheduling().advisorZone());
    Conversation conversation = new Conversation();
    conversation.id = "c-" + UUID.randomUUID();
    conversation.leadId = lead.id();
    conversation.status = ConversationStatus.ACTIVE;
    conversation.stage = Stage.ENGAGE;
    conversation.flagsJson =
        Flags.write(zone.assumed() ? EnumSet.of(Flag.TZ_ASSUMED) : EnumSet.noneOf(Flag.class));
    conversation.endReason = EndReason.NONE;
    conversation.setEndReasonHistory(List.of());
    conversation.handoffReason = HandoffReason.NONE;
    conversation.leadZoneId = zone.zone().getId();
    conversation.promptVersion = start.bundle().id();
    conversation.model = current.agent().model();
    conversation.reasoningEffort = current.agent().reasoning().value();
    conversation.appVersion = appVersion;
    conversation.source = start.source();
    conversation.createdAt = now;
    conversation.updatedAt = now;
    return conversation;
  }

  /** Turn 0: the provider variant for a lead with a fund, the first-time variant otherwise. */
  private Message storeOpener(
      Conversation conversation, Lead lead, PromptBundle bundle, Instant now) {
    Template template = lead.hasProvider() ? Template.OPENER_PROVIDER : Template.OPENER_NO_PROVIDER;
    String text = bundle.templates().render(template, lead);
    Message opener = Message.outbound(conversation.id, 0, text, MessageSource.TEMPLATE, now);
    messages.save(opener);
    log.info(
        "conversation {} started for lead {} with {}, {} chars",
        conversation.id,
        lead.id(),
        template,
        opener.charCount());
    return opener;
  }
}
