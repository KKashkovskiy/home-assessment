package com.enrola.anna.guard;

import com.enrola.anna.lead.Lead;
import java.util.List;

/**
 * What the reply checks need besides the reply. {@code offeredLabels} and {@code bookingLabel} hold
 * the only clock times a reply may name; with none, any clock time fails. {@code terminal} means
 * the reply closes the conversation, so it must ask nothing.
 */
public record ReplyContext(
    Lead lead,
    String canary,
    List<String> offeredLabels,
    String bookingLabel,
    boolean goalMet,
    boolean terminal,
    int maxChars) {}
