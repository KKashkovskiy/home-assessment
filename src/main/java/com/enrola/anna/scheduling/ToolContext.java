package com.enrola.anna.scheduling;

import com.enrola.anna.config.Settings;
import com.enrola.anna.lead.Lead;
import java.time.ZoneId;
import java.util.List;

/**
 * The conversation a function call is answered for, as plain values: its id, its lead, the lead's
 * zone and the slots it was last offered, the only ones book_call accepts; and the scheduling
 * settings its turn runs on.
 */
public record ToolContext(
    String conversationId,
    Lead lead,
    ZoneId leadZone,
    List<Slot> lastOffered,
    Settings.Scheduling scheduling) {}
