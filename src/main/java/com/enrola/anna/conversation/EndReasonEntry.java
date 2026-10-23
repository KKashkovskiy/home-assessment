package com.enrola.anna.conversation;

import com.enrola.anna.agent.EndReason;
import java.time.Instant;

/** One end-reason transition: the conversation's history keeps every one, oldest first. */
public record EndReasonEntry(EndReason reason, Instant at) {}
