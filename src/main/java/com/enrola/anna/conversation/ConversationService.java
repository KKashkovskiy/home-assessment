package com.enrola.anna.conversation;

import com.enrola.anna.AppClock;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.config.AgentProperties;
import com.enrola.anna.config.SchedulingProperties;
import com.enrola.anna.guard.Suppressions;
import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.LeadRepository;
import com.enrola.anna.prompt.PromptBundle;
import com.enrola.anna.prompt.PromptLoader;
import com.enrola.anna.prompt.Template;
import com.enrola.anna.scheduling.LeadZones;
import java.time.Instant;
import java.time.ZoneId;
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
 * Starts conversations and reads them back; {@link InboundTurns} runs their turns. Throws {@link
 * NotFoundException} and {@link ConflictException}; status codes are the API's business.
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
  private final PromptLoader prompts;
  private final Suppressions suppressions;
  private final TurnLocks locks;
  private final TransactionTemplate tx;
  private final AppClock clock;
  private final AgentProperties agent;
  private final ZoneId advisorZone;
  private final String appVersion;

  public ConversationService(
      LeadRepository leads,
      ConversationRepository conversations,
      MessageRepository messages,
      PromptLoader prompts,
      Suppressions suppressions,
      TurnLocks locks,
      TransactionTemplate tx,
      AppClock clock,
      AgentProperties agent,
      SchedulingProperties scheduling,
      @Value("${anna.app-version}") String appVersion) {
    this.leads = leads;
    this.conversations = conversations;
    this.messages = messages;
    this.prompts = prompts;
    this.suppressions = suppressions;
    this.locks = locks;
    this.tx = tx;
    this.clock = clock;
    this.agent = agent;
    this.advisorZone = scheduling.advisorZone();
    this.appVersion = appVersion;
  }

  /**
   * Creates the conversation and stores the template opener as turn 0. An opted-out phone gets a
   * SUPPRESSED conflict; a lead with an open conversation gets an ACTIVE conflict naming it, and
   * one whose last conversation ended ABUSIVE an ABUSIVE conflict naming that, so only a deliberate
   * {@link #forceStart} texts them again.
   */
  public TurnResponse start(String leadId) {
    Lead lead = requireLead(leadId);
    return locks.forLead(leadId, () -> tx.execute(_ -> openIfNoneOpen(lead)));
  }

  /**
   * Like {@link #start}, but opens a second conversation beside an open one, or after an abusive
   * end. An opted-out phone is still refused: nothing overrides an opt-out.
   */
  public TurnResponse forceStart(String leadId) {
    Lead lead = requireLead(leadId);
    return locks.forLead(leadId, () -> tx.execute(_ -> openUnlessSuppressed(lead)));
  }

  /**
   * The conversation as it stands: state plus transcript, with no reply. Bodies from a turn that
   * carried health information on are hidden.
   */
  @Transactional(readOnly = true)
  public TurnResponse view(String conversationId) {
    return TurnResponse.of(
        conversations.require(conversationId),
        null,
        messages.transcript(conversationId),
        clock.now());
  }

  /** Like {@link #view}, with every body shown, health information included. */
  @Transactional(readOnly = true)
  public TurnResponse viewWithHealth(String conversationId) {
    return TurnResponse.withHealth(
        conversations.require(conversationId), messages.transcript(conversationId), clock.now());
  }

  private Lead requireLead(String leadId) {
    return leads.findById(leadId).orElseThrow(() -> new NotFoundException("lead", leadId));
  }

  private TurnResponse openIfNoneOpen(Lead lead) {
    rejectIfSuppressed(lead);
    rejectIfOpen(lead);
    rejectIfLastEndedAbusive(lead);
    return open(lead);
  }

  private TurnResponse openUnlessSuppressed(Lead lead) {
    rejectIfSuppressed(lead);
    return open(lead);
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

  private TurnResponse open(Lead lead) {
    Instant now = clock.now();
    PromptBundle bundle = prompts.active();
    Conversation conversation = conversations.save(newConversation(lead, bundle, now));
    Message opener = storeOpener(conversation, lead, bundle, now);
    return TurnResponse.of(conversation, opener.text(), List.of(opener), now);
  }

  /**
   * An unguessable id, because it reaches URLs; version 0 makes {@code save} insert. It records the
   * prompt bundle, model, effort and build it starts on.
   */
  private Conversation newConversation(Lead lead, PromptBundle bundle, Instant now) {
    LeadZones.Resolved zone = LeadZones.resolve(lead.state(), advisorZone);
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
    conversation.promptVersion = bundle.id();
    conversation.model = agent.model();
    conversation.reasoningEffort = agent.reasoningEffort();
    conversation.appVersion = appVersion;
    conversation.source = Conversation.SOURCE_API;
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
