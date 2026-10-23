package com.enrola.anna.web;

import com.enrola.anna.conversation.ConversationRow;
import java.util.List;
import java.util.Map;

/**
 * Everything the Conversations page renders: the list for a filter, out of how many in all, the
 * open conversation or null, the quick replies, the start panel and a notice or null. The rows'
 * summaries are already masked when the PII mask is on.
 */
public record ConsolePage(
    ConversationFilter filter,
    List<ConversationRow> rows,
    long total,
    Open open,
    List<QuickReplies.QuickReply> quickReplies,
    StartPanel start,
    Notice notice) {

  /** The open conversation: its chat and state, and its inspector. */
  public record Open(ConsoleView view, InspectorView inspector) {}

  /** Whether {@code conversationId} is the open conversation, so its list row is marked. */
  public boolean opened(String conversationId) {
    return open != null && open.view().conversation().id.equals(conversationId);
  }

  /** The start panel: the form as filled, its field errors, and whether it shows open. */
  public record StartPanel(StartForm form, Map<String, String> errors, boolean open) {

    static final StartPanel CLOSED = new StartPanel(StartForm.EMPTY, Map.of(), false);
    static final StartPanel OPEN = new StartPanel(StartForm.EMPTY, Map.of(), true);
  }

  ConsolePage withStart(StartPanel panel) {
    return new ConsolePage(filter, rows, total, open, quickReplies, panel, notice);
  }

  ConsolePage withNotice(Notice shown) {
    return new ConsolePage(filter, rows, total, open, quickReplies, start, shown);
  }
}
