package com.enrola.anna.conversation;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.Stage;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One SMS conversation with one lead: the aggregate every turn updates. A row with public fields,
 * not a bean with invariants; {@link ConversationService}, {@link InboundTurns} and {@link
 * DecisionApplier} change it, and {@link StateRules} alone sets its terminal and handoff fields.
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

  // What the conversation started on: prompt bundle, model, reasoning effort, app build.
  public String promptVersion;
  public String model;
  public String reasoningEffort;
  public String appVersion;

  public String source;

  /** What the model has gathered about the lead, as a JSON object; null before its first turn. */
  public String extractedJson;

  /** The model's latest one-line summary for the advisor. */
  public String summary;

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

  public JsonNode extracted() {
    return extractedJson == null ? null : JSON.readTree(extractedJson);
  }

  /** Every end-reason transition so far, oldest first. */
  public List<EndReasonEntry> endReasonHistory() {
    return JSON.readValue(endReasonHistoryJson, HISTORY);
  }

  public void setEndReasonHistory(List<EndReasonEntry> history) {
    endReasonHistoryJson = JSON.writeValueAsString(history);
  }
}
