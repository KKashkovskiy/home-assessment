package com.enrola.anna.conversation;

import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.Stage;
import java.time.Instant;
import java.util.EnumSet;

/**
 * One row of the console's conversation list: the columns it shows, joined with the lead's name and
 * state. Read straight from {@link ConversationRepository#search}, never built from the entity.
 */
public record ConversationRow(
    String id,
    String givenName,
    String leadState,
    ConversationStatus status,
    Stage stage,
    Instant updatedAt,
    String flagsJson,
    String summary,
    Source source,
    boolean needsHuman) {

  public EnumSet<Flag> flags() {
    return Flags.parse(flagsJson);
  }

  /** This row with {@code shown} as its summary, as when the console masks it. */
  public ConversationRow withSummary(String shown) {
    return new ConversationRow(
        id, givenName, leadState, status, stage, updatedAt, flagsJson, shown, source, needsHuman);
  }
}
