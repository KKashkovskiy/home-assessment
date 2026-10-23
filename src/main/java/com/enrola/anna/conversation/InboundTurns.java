package com.enrola.anna.conversation;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.config.SmsProperties;
import com.enrola.anna.guard.StopKeywords;
import com.enrola.anna.guard.Suppressions;
import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.LeadRepository;
import com.enrola.anna.lead.Suppression;
import com.enrola.anna.prompt.PromptLoader;
import com.enrola.anna.prompt.Template;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * One inbound SMS to one turn, serialised per conversation and idempotent on the provider message
 * id. A turn is three transactions, never one: the claim of its turn number commits before any
 * work; the work, the model call included, runs outside a transaction; and the finish writes the
 * reply, the conversation and the turn row together. Each is REQUIRES_NEW, so none joins a caller's
 * transaction.
 */
@Service
public class InboundTurns {

  private static final Logger log = LoggerFactory.getLogger(InboundTurns.class);

  private final LeadRepository leads;
  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final TurnRepository turns;
  private final PromptLoader prompts;
  private final Suppressions suppressions;
  private final TurnLocks locks;
  private final AgentTurn agentTurn;
  private final TransactionTemplate tx;
  private final JsonMapper json;
  private final Clock clock;
  private final SmsProperties sms;

  public InboundTurns(
      LeadRepository leads,
      ConversationRepository conversations,
      MessageRepository messages,
      TurnRepository turns,
      PromptLoader prompts,
      Suppressions suppressions,
      TurnLocks locks,
      AgentTurn agentTurn,
      PlatformTransactionManager transactions,
      JsonMapper json,
      Clock clock,
      SmsProperties sms) {
    this.leads = leads;
    this.conversations = conversations;
    this.messages = messages;
    this.turns = turns;
    this.prompts = prompts;
    this.suppressions = suppressions;
    this.locks = locks;
    this.agentTurn = agentTurn;
    this.tx = new TransactionTemplate(transactions);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.json = json;
    this.clock = clock;
    this.sms = sms;
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
    if (inbound.charCount() > sms.maxInboundChars()) {
      conversation.addFlag(Flag.INPUT_TRUNCATED);
    }
    TurnOutcome outcome = pipeline(conversation, inbound, now);
    return tx.execute(_ -> persistTurn(conversation, inbound, outcome, now));
  }

  /** The turn's steps in order; the first that decides what to send, or that nothing is, wins. */
  private TurnOutcome pipeline(Conversation conversation, Message inbound, Instant now) {
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
    return agentTurn.run(conversation, lead, inbound.turnNo(), now);
  }

  /**
   * A hard-terminal conversation answers nothing and keeps its state, but a STOP still suppresses
   * the phone: an opt-out is honoured however the conversation ended.
   */
  private TurnOutcome endedSilently(Conversation conversation, Message inbound, Instant now) {
    if (StopKeywords.isStop(inbound.text())) {
      suppress(conversation, leadOf(conversation), now);
    }
    return TurnOutcome.silent("ended " + conversation.endReason);
  }

  /** The phone opted out on another conversation: this one ends too, and says nothing. */
  private TurnOutcome phoneOptedOut(Conversation conversation, Instant now) {
    StateRules.end(conversation, EndReason.UNSUBSCRIBED, now);
    return TurnOutcome.silent("phone opted out");
  }

  /** Suppresses the phone, ends the conversation and confirms, with no model involved. */
  private TurnOutcome optOut(Conversation conversation, Lead lead, Instant now) {
    suppress(conversation, lead, now);
    StateRules.end(conversation, EndReason.UNSUBSCRIBED, now);
    String confirmation =
        prompts
            .load(conversation.promptVersion)
            .templates()
            .render(Template.UNSUBSCRIBE_CONFIRMATION, lead);
    return TurnOutcome.fixed(confirmation, "opt-out keyword");
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

  /** A writer that bypassed the lock fails here on the conversation's version. */
  private TurnResponse persistTurn(
      Conversation conversation, Message inbound, TurnOutcome outcome, Instant now) {
    saveReply(conversation, inbound, outcome, now);
    conversation.updatedAt = now;
    conversations.save(conversation);
    TurnResponse response =
        TurnResponse.of(conversation, outcome.reply(), messages.transcript(conversation.id), now);
    turns.save(Turn.of(inbound, outcome, json.writeValueAsString(response), now));
    log.info(
        "conversation {} turn {} {}: {}",
        conversation.id,
        inbound.turnNo(),
        outcome.status(),
        outcome.decidedBy());
    return response;
  }

  private void saveReply(
      Conversation conversation, Message inbound, TurnOutcome outcome, Instant now) {
    if (outcome.reply() != null) {
      messages.save(
          Message.outbound(
              conversation.id, inbound.turnNo(), outcome.reply(), outcome.source(), now));
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
}
