package com.enrola.anna.conversation;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.scheduling.Slot;
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
 * not a bean with invariants; {@link ConversationService} creates it, the steps of {@link
 * TurnPipeline} change it, and {@link StateRules} alone sets its terminal and handoff fields.
 */
public class Conversation {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<EndReasonEntry>> HISTORY = new TypeReference<>() {};
  private static final TypeReference<List<Slot>> SLOTS = new TypeReference<>() {};

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

  public Source source;

  /** What the model has gathered about the lead, as a JSON object; null before its first turn. */
  public String extractedJson;

  /** The model's latest one-line summary for the advisor. */
  public String summary;

  // Counts only code moves; the model reads them in the turn context. Agent turns are the
  // messages sent after the opener, and post-terminal replies are those sent on or after the turn
  // that ended it.
  public int objectionsUsed;
  public int offTopicStreak;
  public int agentTurns;
  public int postTerminalReplies;

  /** The turn that first carried HEALTH_INFO, null before; bodies from it on are hidden. */
  public Integer healthInfoTurn;

  /** The latest availability result, whose labels are the only clock times a reply may name. */
  public String lastOfferedSlotsJson;

  /** Tool errors in a row, across turns; an answer resets it, and the second error hands off. */
  public int upstreamErrors;

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

  /** True for a message from the first HEALTH_INFO turn on, whose body is hidden by default. */
  public boolean hidesBody(int turnNo) {
    return healthInfoTurn != null && turnNo >= healthInfoTurn;
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

  /** The latest availability result's slots, in offer order; empty before the first. */
  public List<Slot> lastOfferedSlots() {
    return lastOfferedSlotsJson == null ? List.of() : JSON.readValue(lastOfferedSlotsJson, SLOTS);
  }

  /** The labels of {@link #lastOfferedSlots()}, what a reply may name. */
  public List<String> lastOfferedLabels() {
    return lastOfferedSlots().stream().map(Slot::label).toList();
  }

  /** Replaces the last offer, never adds to it: only the latest result may be offered. */
  public void setLastOfferedSlots(List<Slot> slots) {
    lastOfferedSlotsJson = JSON.writeValueAsString(slots);
  }
}
