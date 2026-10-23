package com.enrola.anna.conversation;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * One SMS conversation with one lead: the aggregate every turn updates. A row with public fields,
 * not a bean with invariants; {@link ConversationService} and {@link InboundTurns} change it, and
 * {@link StateRules} alone sets its terminal and handoff fields.
 */
public class Conversation {

  /** The source of a conversation the platform started through the API. */
  public static final String SOURCE_API = "API";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<EndReasonEntry>> HISTORY = new TypeReference<>() {};

  @Id public String id;
  public String leadId;

  public ConversationStatus status;

  public Stage stage;
  public String flagsJson;
  public boolean goalMet;
  public boolean unsubscribed;
  public boolean ended;
  public boolean needsHuman;
  public EndReason endReason;
  public String endReasonHistoryJson;
  public HandoffReason handoffReason;

  /**
   * Resolved from the lead's state at start and never recomputed, so the lead's clock stays put.
   */
  public String leadZoneId;

  public String promptVersion;
  public String source;
  public Instant createdAt;
  public Instant updatedAt;
  @Version public long version;

  public EnumSet<Flag> flags() {
    return Flags.parse(flagsJson);
  }

  public void addFlag(Flag flag) {
    EnumSet<Flag> flags = flags();
    flags.add(flag);
    flagsJson = Flags.write(flags);
  }

  /** Every end-reason transition so far, oldest first. */
  public List<EndReasonEntry> endReasonHistory() {
    return JSON.readValue(endReasonHistoryJson, HISTORY);
  }

  public void setEndReasonHistory(List<EndReasonEntry> history) {
    endReasonHistoryJson = JSON.writeValueAsString(history);
  }
}
