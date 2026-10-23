package com.enrola.anna.eval;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsStore;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.conversation.ConversationRepository;
import com.enrola.anna.conversation.ConversationService;
import com.enrola.anna.conversation.Direction;
import com.enrola.anna.conversation.Message;
import com.enrola.anna.conversation.MessageRepository;
import com.enrola.anna.conversation.TraceEvent;
import com.enrola.anna.conversation.TraceEventRepository;
import com.enrola.anna.conversation.TurnRepository;
import com.enrola.anna.conversation.TurnResponse;
import com.enrola.anna.guard.ReplyContext;
import com.enrola.anna.lead.LeadRepository;
import com.enrola.anna.prompt.PromptStore;
import java.util.List;

/**
 * Reads back what a conversation's latest turn left in the database, and what its replies are
 * checked against: the lead, the prompt's canary, the offered and booked labels as the product's
 * own view reports them, and the length limit; and, for the judges, a trial's whole conversation.
 */
final class TurnRowsReader {

  private final ConversationService conversations;
  private final ConversationRepository rows;
  private final MessageRepository messages;
  private final TurnRepository turns;
  private final TraceEventRepository trace;
  private final LeadRepository leads;
  private final PromptStore prompts;
  private final SettingsStore settings;

  TurnRowsReader(
      ConversationService conversations,
      ConversationRepository rows,
      MessageRepository messages,
      TurnRepository turns,
      TraceEventRepository trace,
      LeadRepository leads,
      PromptStore prompts,
      SettingsStore settings) {
    this.conversations = conversations;
    this.rows = rows;
    this.messages = messages;
    this.turns = turns;
    this.trace = trace;
    this.leads = leads;
    this.prompts = prompts;
    this.settings = settings;
  }

  /** The rows of the conversation's latest inbound turn. */
  TurnRows latestTurn(String conversationId) {
    Conversation conversation = rows.require(conversationId);
    int turnNo = messages.lastClaimedTurn(conversationId);
    List<TraceEvent> events =
        trace.findByConversationIdOrderByTurnNoAscSeqAsc(conversationId).stream()
            .filter(event -> event.turnNo() == turnNo)
            .toList();
    List<Message> outbound =
        messages.transcript(conversationId).stream()
            .filter(message -> message.turnNo() == turnNo && message.direction() == Direction.OUT)
            .toList();
    return new TurnRows(
        conversation,
        turnNo,
        turns.findByConversationIdAndTurnNo(conversationId, turnNo).orElse(null),
        events,
        outbound,
        replyContext(conversation));
  }

  /** What a judge reads about a finished trial: its conversation's lead and every SMS in order. */
  TrialTranscript transcript(Scenario scenario, Trial trial) {
    Conversation conversation = rows.require(trial.conversationId());
    return new TrialTranscript(
        scenario,
        trial.trialNo(),
        leads.findById(conversation.leadId).orElseThrow(),
        messages.transcript(conversation.id));
  }

  /** The suite-wide checks ask nothing of questions, so goal and closing only fill the record. */
  private ReplyContext replyContext(Conversation conversation) {
    Settings current = settings.current();
    TurnResponse view = conversations.view(conversation.id);
    return new ReplyContext(
        leads.findById(conversation.leadId).orElseThrow(),
        prompts.bundle(conversation.promptVersion, current.persona()).canary(),
        view.lastOfferedSlots(),
        view.booking() == null ? null : view.booking().localLabel(),
        view.goalMet(),
        view.conversationEnded(),
        current.limits().maxChars());
  }
}
