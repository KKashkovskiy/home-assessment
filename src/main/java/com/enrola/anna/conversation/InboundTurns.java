package com.enrola.anna.conversation;

import com.enrola.anna.AppClock;
import com.enrola.anna.scheduling.BookingRepository;
import java.time.Instant;
import java.util.Map;
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
 * id. A turn is three transactions, never one. The claim of its turn number commits before any
 * work. The work, the model call included, runs outside a transaction. The finish writes the reply,
 * the conversation, the turn row and the STATE_DIFF event of what the turn changed together, so a
 * turn that dies before the finish leaves no diff of changes never made. Each is REQUIRES_NEW, so
 * none joins a caller's transaction.
 */
@Service
public class InboundTurns {

  private static final Logger log = LoggerFactory.getLogger(InboundTurns.class);

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final TurnRepository turns;
  private final BookingRepository bookings;
  private final TurnLocks locks;
  private final TurnPipeline pipeline;
  private final TraceLog trace;
  private final TransactionTemplate tx;
  private final JsonMapper json;
  private final AppClock clock;

  public InboundTurns(
      ConversationRepository conversations,
      MessageRepository messages,
      TurnRepository turns,
      BookingRepository bookings,
      TurnLocks locks,
      TurnPipeline pipeline,
      TraceLog trace,
      PlatformTransactionManager transactions,
      JsonMapper json,
      AppClock clock) {
    this.conversations = conversations;
    this.messages = messages;
    this.turns = turns;
    this.bookings = bookings;
    this.locks = locks;
    this.pipeline = pipeline;
    this.trace = trace;
    this.tx = new TransactionTemplate(transactions);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.json = json;
    this.clock = clock;
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
                    clock.now())));
  }

  private TurnResponse run(Conversation conversation, Message inbound) {
    Instant now = clock.now();
    StateSnapshot before = StateSnapshot.of(conversation);
    TurnOutcome outcome = pipeline.run(conversation, inbound, now);
    Map<String, StateChange> changes = before.changesTo(StateSnapshot.of(conversation));
    return tx.execute(
        _ -> {
          TurnResponse response = persistTurn(conversation, inbound, outcome, now);
          trace.record(
              conversation.id,
              inbound.turnNo(),
              new TraceLog.Entry(TraceEventType.STATE_DIFF, "state", TraceOutcome.INFO, changes));
          return response;
        });
  }

  /** A writer that bypassed the lock fails here on the conversation's version. */
  private TurnResponse persistTurn(
      Conversation conversation, Message inbound, TurnOutcome outcome, Instant now) {
    saveReply(conversation, inbound, outcome, now);
    conversation.updatedAt = now;
    conversations.save(conversation);
    TurnResponse response =
        TurnResponse.of(
            new TurnResponse.Rows(
                conversation,
                bookings.findByConversationId(conversation.id).orElse(null),
                messages.transcript(conversation.id)),
            new TurnResponse.Answer(outcome.reply(), outcome.noReply()),
            now);
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
    if (outcome.reply() == null) {
      return;
    }
    messages.save(
        outcome.repaired()
            ? Message.repairedReply(conversation.id, inbound.turnNo(), outcome.reply(), now)
            : Message.outbound(
                conversation.id, inbound.turnNo(), outcome.reply(), outcome.source(), now));
  }
}
