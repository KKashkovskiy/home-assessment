package com.enrola.anna.guard;

import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.lead.Lead;
import java.util.List;

/**
 * What the reply checks need besides the reply. {@code leadIntent} is the decision's reading of the
 * lead's message, null when no model decided the turn. {@code offeredLabels} and {@code
 * bookingLabel} hold the only clock times a reply may name; with none, any clock time fails. {@code
 * terminal} means the reply closes the conversation, so it must ask nothing.
 */
public record ReplyContext(
    Lead lead,
    LeadIntent leadIntent,
    String canary,
    List<String> offeredLabels,
    String bookingLabel,
    boolean goalMet,
    boolean terminal,
    int maxChars) {}
