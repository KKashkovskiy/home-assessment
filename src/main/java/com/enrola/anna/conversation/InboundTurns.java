package com.enrola.anna.conversation;

import com.enrola.anna.guard.StopKeywords;
import com.enrola.anna.guard.Suppressions;
import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.LeadRepository;
import com.enrola.anna.lead.Suppression;
import com.enrola.anna.prompt.Template;
import com.enrola.anna.prompt.Templates;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * One inbound SMS to one turn, serialised per conversation and idempotent on the provider message
 * id. A turn is three transactions, never one: the claim of its turn number commits before any
 * work, the work runs outside a transaction, and the finish writes the reply, the conversation and
 * the turn row together. Each is REQUIRES_NEW, so none joins a caller's transaction.
 */
@Service
public class InboundTurns {

  private static final Logger log = LoggerFactory.getLogger(InboundTurns.class);

  private final LeadRepository leads;
  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final TurnRepository turns;
  private final Templates templates;
  private final Suppressions suppressions;
  private final TurnLocks locks;
  private final TransactionTemplate tx;
  private final JsonMapper json;
  private final Clock clock;
  private final int maxInboundChars;

  public InboundTurns(
      LeadRepository leads,
      ConversationRepository conversations,
      MessageRepository messages,
      TurnRepository turns,
      Templates templates,
      Suppressions suppressions,
      TurnLocks locks,
      PlatformTransactionManager transactions,
      JsonMapper json,
      Clock clock,
      @Value("${anna.sms.max-inbound-chars}") int maxInboundChars) {
    this.leads = leads;
    this.conversations = conversations;
    this.messages = messages;
    this.turns = turns;
    this.templates = templates;
    this.suppressions = suppressions;
    this.locks = locks;
    this.tx = new TransactionTemplate(transactions);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.json = json;
    this.clock = clock;
    this.maxInboundChars = maxInboundChars;
  }

  /**
   * Runs the turn for {@code text}. A repeat of a finished turn's provider message id returns its
   * stored response and writes nothing; a repeat of a turn that was claimed but never finished runs
   * it again under the same number. A message without a provider id is always a new turn.
   */
  public TurnResponse handle(String conversationId, String text, String providerMessageId) {
    if (!conversations.existsById(conversationId)) {
      throw new NotFoundException("conversation", conversationId);
    }
    return locks.forConversation(
        conversationId,
        () -> {
          Conversation conversation = conversations.require(conversationId);
          Optional<Message> received = alreadyReceived(conversationId, providerMessageId);
          if (received.isPresent()) {
            return replayOrRerun(conversation, received.get());
          }
          return run(conversation, claim(conversation, text, providerMessageId));
        });
  }

  private Optional<Message> alreadyReceived(String conversationId, String providerMessageId) {
    return providerMessageId == null
        ? Optional.empty()
        : messages.findByConversationIdAndProviderMessageId(conversationId, providerMessageId);
  }

  private TurnResponse replayOrRerun(Conversation conversation, Message inbound) {
    return turns
        .findByConversationIdAndTurnNo(conversation.id, inbound.turnNo())
        .map(finished -> replay(conversation, finished))
        .orElseGet(() -> run(conversation, inbound));
  }

  private TurnResponse replay(Conversation conversation, Turn finished) {
    log.info("conversation {} turn {} replayed", conversation.id, finished.turnNo());
    return json.readValue(finished.responseJson(), TurnResponse.class);
  }

  /** Stores the inbound under the next turn number and commits, so the number is never reused. */
  private Message claim(Conversation conversation, String text, String providerMessageId) {
    return tx.execute(
        _ ->
            messages.save(
                Message.inbound(
                    conversation.id,
                    messages.lastClaimedTurn(conversation.id) + 1,
                    text.strip(),
                    providerMessageId,
                    clock.instant())));
  }

  /**
   * Text over {@code anna.sms.max-inbound-chars} stays whole in its row and flags the conversation
   * INPUT_TRUNCATED.
   */
  private TurnResponse run(Conversation conversation, Message inbound) {
    Instant now = clock.instant();
    if (inbound.charCount() > maxInboundChars) {
      conversation.addFlag(Flag.INPUT_TRUNCATED);
    }
    Out out = pipeline(conversation, inbound, now);
    return tx.execute(_ -> persistTurn(conversation, inbound, out, now));
  }

  /** The turn's steps in order; the first that decides what to send, or that nothing is, wins. */
  private Out pipeline(Conversation conversation, Message inbound, Instant now) {
    if (conversation.ended && StateRules.isHardTerminal(conversation.endReason)) {
      return endedSilently(conversation, inbound, now);
    }
    Lead lead = leadOf(conversation);
    if (suppressions.optedOut(lead.phone())) {
      return phoneOptedOut(conversation, now);
    }
    if (StopKeywords.isStop(inbound.text())) {
      return optOut(conversation, lead, now);
    }
    return agentUnavailable(conversation);
  }

  /**
   * A hard-terminal conversation answers nothing and keeps its state, but a STOP still suppresses
   * the phone: an opt-out is honoured however the conversation ended.
   */
  private Out endedSilently(Conversation conversation, Message inbound, Instant now) {
    if (StopKeywords.isStop(inbound.text())) {
      suppress(conversation, leadOf(conversation), now);
    }
    return Out.silent("ended " + conversation.endReason);
  }

  /** The phone opted out on another conversation: this one ends too, and says nothing. */
  private Out phoneOptedOut(Conversation conversation, Instant now) {
    StateRules.end(conversation, EndReason.UNSUBSCRIBED, now);
    return Out.silent("phone opted out");
  }

  /** Suppresses the phone, ends the conversation and confirms, with no model involved. */
  private Out optOut(Conversation conversation, Lead lead, Instant now) {
    suppress(conversation, lead, now);
    StateRules.end(conversation, EndReason.UNSUBSCRIBED, now);
    return Out.fixed(templates.render(Template.UNSUBSCRIBE_CONFIRMATION, lead), "opt-out keyword");
  }

  /**
   * Under the lead's lock, so a start for the same lead cannot pass its check in between. A busy
   * lead is reported as this conversation being busy, since the platform retries the message.
   */
  private void suppress(Conversation conversation, Lead lead, Instant now) {
    try {
      locks.forLead(lead.id(), () -> suppressions.record(lead, Suppression.Reason.KEYWORD, now));
    } catch (BusyException _) {
      throw BusyException.conversation(conversation.id);
    }
  }

  /**
   * No model is configured: the message is kept, a human is asked to look, nothing is sent, and the
   * conversation stays open.
   */
  private Out agentUnavailable(Conversation conversation) {
    StateRules.handoff(conversation, HandoffReason.ERROR);
    return Out.silent("no model configured");
  }

  /** A writer that bypassed the lock fails here on the conversation's version. */
  private TurnResponse persistTurn(
      Conversation conversation, Message inbound, Out out, Instant now) {
    saveReply(conversation, inbound, out, now);
    conversation.updatedAt = now;
    conversations.save(conversation);
    TurnResponse response =
        TurnResponse.of(conversation, out.reply(), messages.transcript(conversation.id), now);
    turns.save(Turn.of(inbound, out.status(), json.writeValueAsString(response), now));
    log.info(
        "conversation {} turn {} {}: {}",
        conversation.id,
        inbound.turnNo(),
        out.status(),
        out.outcome());
    return response;
  }

  private void saveReply(Conversation conversation, Message inbound, Out out, Instant now) {
    if (out.reply() != null) {
      messages.save(
          Message.outbound(conversation.id, inbound.turnNo(), out.reply(), out.source(), now));
    }
  }

  private Lead leadOf(Conversation conversation) {
    return leads
        .findById(conversation.leadId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "lead " + conversation.leadId + " missing for " + conversation.id));
  }

  /**
   * What a turn sends: a reply and its source, or nothing. {@code outcome} names the step that
   * decided, for the log.
   */
  private record Out(String reply, MessageSource source, TurnStatus status, String outcome) {

    static Out fixed(String reply, String outcome) {
      return new Out(reply, MessageSource.FIXED, TurnStatus.OK, outcome);
    }

    static Out silent(String outcome) {
      return new Out(null, null, TurnStatus.NO_REPLY, outcome);
    }
  }
}
