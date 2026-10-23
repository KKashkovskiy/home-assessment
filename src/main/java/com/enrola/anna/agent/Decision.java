package com.enrola.anna.agent;

import java.util.List;
import java.util.Objects;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The model's structured output, classification first, then the reply and the state it asks for.
 * Field names and enums mirror {@code prompts/v1/agent-decision.schema.json}; {@code
 * DecisionSchemaTest} fails when they drift. {@code goalMet} is advisory: code owns the real one.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Decision(
    LeadIntent leadIntent,
    Stage stage,
    ObjectionType objectionType,
    List<Flag> flags,
    Extracted extracted,
    String summary,
    boolean sendReply,
    String reply,
    boolean goalMet,
    boolean unsubscribed,
    boolean conversationEnded,
    EndReason endReason,
    boolean needsHuman,
    HandoffReason handoffReason) {

  /** Every field the schema does not allow to be null is required here too, flags included. */
  public Decision {
    Objects.requireNonNull(leadIntent, "lead_intent");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(objectionType, "objection_type");
    flags = List.copyOf(flags);
    Objects.requireNonNull(extracted, "extracted");
    Objects.requireNonNull(reply, "reply");
    Objects.requireNonNull(endReason, "end_reason");
    Objects.requireNonNull(handoffReason, "handoff_reason");
  }

  /**
   * Facts about the lead gathered so far; UNKNOWN or null means not known. Never health details.
   * {@code preferredDay} is an ISO date in the lead's zone.
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Extracted(
      CoverageGoal coverageGoal,
      CoverType coverType,
      String currentCoverNote,
      String preferredDay,
      TimeOfDay preferredTimeOfDay) {

    public Extracted {
      Objects.requireNonNull(coverageGoal, "coverage_goal");
      Objects.requireNonNull(coverType, "cover_type");
      Objects.requireNonNull(preferredTimeOfDay, "preferred_time_of_day");
    }
  }

  /** Save money, improve cover, both, or a first policy. */
  public enum CoverageGoal {
    SAVE,
    IMPROVE,
    BOTH,
    FIRST_COVER,
    UNKNOWN
  }

  /** Hospital, extras or both. */
  public enum CoverType {
    HOSPITAL,
    EXTRAS,
    BOTH,
    UNKNOWN
  }

  /** The part of day the lead prefers for the call. */
  public enum TimeOfDay {
    ANY,
    MORNING,
    MIDDAY,
    AFTERNOON,
    EVENING,
    UNKNOWN
  }
}
