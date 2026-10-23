package com.enrola.anna.web;

import com.enrola.anna.agent.Flag;
import com.enrola.anna.conversation.ConversationRepository;
import com.enrola.anna.conversation.ConversationRow;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Assembles the {@link ConsolePage}: the list for a filter with its summaries masked when the mask
 * is on, the total it is out of, and the quick replies. The open conversation comes from {@link
 * ConsoleViews} and its inspector from {@link TraceView}.
 */
@Component
public class ConsolePages {

  private final ConversationRepository conversations;
  private final QuickReplies quickReplies;

  public ConsolePages(ConversationRepository conversations, QuickReplies quickReplies) {
    this.conversations = conversations;
    this.quickReplies = quickReplies;
  }

  /** The page with no conversation open, the start panel closed and no notice. */
  public ConsolePage page(ConversationFilter filter, boolean mask) {
    return page(filter, null, mask);
  }

  /** The page with {@code open} beside the list, or none when it is null. */
  public ConsolePage page(ConversationFilter filter, ConsolePage.Open open, boolean mask) {
    return new ConsolePage(
        filter,
        rows(filter, mask),
        conversations.count(),
        open,
        quickReplies.load(),
        ConsolePage.StartPanel.CLOSED,
        null);
  }

  private List<ConversationRow> rows(ConversationFilter filter, boolean mask) {
    List<ConversationRow> rows =
        conversations.search(
            filter.status(),
            filter.endReason(),
            filter.stage(),
            filter.flag(),
            filter.needsHuman(),
            filter.source(),
            filter.q());
    return mask ? rows.stream().map(ConsolePages::masked).toList() : rows;
  }

  private static ConversationRow masked(ConversationRow row) {
    return row.withSummary(PiiMask.freeText(row.summary(), row.flags().contains(Flag.HEALTH_INFO)));
  }
}
