package com.enrola.anna.conversation;

import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.LeadRepository;
import com.enrola.anna.prompt.Template;
import com.enrola.anna.prompt.Templates;
import com.enrola.anna.scheduling.LeadZones;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place conversation state changes. Throws {@link NotFoundException} and {@link
 * ConflictException}; status codes are the API's business.
 */
@Service
public class ConversationService {

  private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

  private final LeadRepository leads;
  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final Templates templates;
  private final Clock clock;

  public ConversationService(
      LeadRepository leads,
      ConversationRepository conversations,
      MessageRepository messages,
      Templates templates,
      Clock clock) {
    this.leads = leads;
    this.conversations = conversations;
    this.messages = messages;
    this.templates = templates;
    this.clock = clock;
  }

  /**
   * Creates the conversation and stores the template opener as turn 0. A lead with an active
   * conversation gets a conflict naming it.
   */
  @Transactional
  public TurnResponse start(String leadId) {
    Lead lead = leads.findById(leadId).orElseThrow(() -> new NotFoundException("lead", leadId));
    rejectIfActive(leadId);
    Instant now = clock.instant();
    Conversation conversation = conversations.save(newConversation(lead, now));
    Message opener = storeOpener(conversation, lead, now);
    return TurnResponse.of(conversation, opener.text(), List.of(opener), now);
  }

  /** The conversation as it stands: state plus transcript, with no reply. */
  @Transactional(readOnly = true)
  public TurnResponse view(String conversationId) {
    Conversation conversation =
        conversations
            .findById(conversationId)
            .orElseThrow(() -> new NotFoundException("conversation", conversationId));
    List<Message> transcript =
        messages.findByConversationIdOrderByTurnNoAscCreatedAtAsc(conversationId);
    return TurnResponse.of(conversation, null, transcript, clock.instant());
  }

  private void rejectIfActive(String leadId) {
    conversations
        .findActiveByLead(leadId)
        .ifPresent(
            active -> {
              throw new ConflictException(
                  "ACTIVE", "lead " + leadId + " has an active conversation", active.id);
            });
  }

  /** An unguessable id, because it reaches URLs; version 0 makes {@code save} insert. */
  private Conversation newConversation(Lead lead, Instant now) {
    LeadZones.Resolved zone = LeadZones.resolve(lead.state());
    Conversation conversation = new Conversation();
    conversation.id = "c-" + UUID.randomUUID();
    conversation.leadId = lead.id();
    conversation.status = Conversation.ACTIVE;
    conversation.stage = Stage.ENGAGE;
    conversation.flagsJson =
        Flags.write(zone.assumed() ? EnumSet.of(Flag.TZ_ASSUMED) : EnumSet.noneOf(Flag.class));
    conversation.leadZoneId = zone.zone().getId();
    conversation.promptVersion = templates.version();
    conversation.source = Conversation.SOURCE_API;
    conversation.createdAt = now;
    conversation.updatedAt = now;
    return conversation;
  }

  /** Turn 0: the provider variant for a lead with a fund, the first-time variant otherwise. */
  private Message storeOpener(Conversation conversation, Lead lead, Instant now) {
    Template template = lead.hasProvider() ? Template.OPENER_PROVIDER : Template.OPENER_NO_PROVIDER;
    Message opener =
        Message.outbound(
            conversation.id, 0, templates.render(template, lead), MessageSource.TEMPLATE, now);
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
