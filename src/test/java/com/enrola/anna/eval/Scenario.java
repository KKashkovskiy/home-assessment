package com.enrola.anna.eval;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.config.Settings.CalendarPreset;
import com.enrola.anna.conversation.TurnStatus;
import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.Phones;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * One eval scenario file, as {@code evals/README.md} documents it; YAML fields are snake_case. A
 * {@link Turn} is the primary unit: a seeded transcript, one lead message, one turn's expectations.
 * A {@link Story} runs a whole conversation against a simulated lead; it loads so the files stay
 * valid, and the runner skips it.
 */
sealed interface Scenario permits Scenario.Turn, Scenario.Story {

  String id();

  String title();

  List<String> tags();

  LeadRef lead();

  OffsetDateTime clock();

  CalendarPreset calendar();

  Expect expect();

  List<String> judge();

  Config config();

  /** {@code turn} or {@code story}, as the YAML spells it. */
  default String kind() {
    return switch (this) {
      case Turn _ -> "turn";
      case Story _ -> "story";
    };
  }

  /** A seeded lead by name (lauren, john, jane) or an inline record; exactly one is set. */
  @JsonDeserialize(using = LeadRef.Deserializer.class)
  record LeadRef(String name, Lead inline) {

    /** YAML gives a name or an object, and no record constructor takes both shapes. */
    static final class Deserializer extends ValueDeserializer<LeadRef> {
      @Override
      public LeadRef deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode node = context.readTree(parser);
        return node.isString()
            ? new LeadRef(node.stringValue(), null)
            : new LeadRef(null, inline(node));
      }

      /** The id and creation time are the seeder's to assign. */
      private static Lead inline(JsonNode node) {
        return new Lead(
            null,
            node.path("given_name").stringValue(),
            Phones.toE164(node.path("phone").stringValue()),
            text(node, "email"),
            text(node, "state"),
            text(node, "current_provider"),
            text(node, "current_premium"),
            null,
            null,
            Instant.EPOCH);
      }

      private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.stringValue();
      }
    }
  }

  /**
   * Conversation state before the turn: counters, a booked slot label, an end reason, and the
   * closing replies already sent after that end.
   */
  record Seed(
      int objectionsUsed,
      int offTopicStreak,
      String booking,
      EndReason ended,
      int closingRepliesSent) {
    static final Seed NONE = new Seed(0, 0, null, null, 0);
  }

  /** One seeded line: exactly one of agent or lead; offered slot labels only on agent lines. */
  record History(String agent, String lead, List<String> offeredSlots) {}

  /**
   * Overrides for one trial. {@code moderation} is what the offline moderation answers ({@code
   * harassment}, {@code threat}, {@code self_harm}, 0 to 1); {@code maxTurns} replaces the
   * settings' limit. Null means as configured.
   */
  record Config(Map<String, Double> moderation, Integer maxTurns) {
    static final Config NONE = new Config(null, null);
  }

  /** A seeded transcript and state, then {@code message}, the lead message under test. */
  record Turn(
      String id,
      String title,
      List<String> tags,
      LeadRef lead,
      OffsetDateTime clock,
      CalendarPreset calendar,
      Seed seed,
      List<History> history,
      String message,
      Expect expect,
      List<String> judge,
      Config config)
      implements Scenario {

    public Turn {
      tags = tags == null ? List.of() : tags;
      seed = seed == null ? Seed.NONE : seed;
      history = history == null ? List.of() : history;
      judge = judge == null ? List.of() : judge;
      config = config == null ? Config.NONE : config;
    }
  }

  /** A whole conversation: a persona for the simulated lead and when to stop. */
  record Story(
      String id,
      String title,
      List<String> tags,
      LeadRef lead,
      OffsetDateTime clock,
      CalendarPreset calendar,
      String persona,
      List<String> stopWhen,
      Expect expect,
      List<String> judge,
      Config config)
      implements Scenario {

    public Story {
      tags = tags == null ? List.of() : tags;
      judge = judge == null ? List.of() : judge;
      config = config == null ? Config.NONE : config;
    }
  }

  /**
   * What a scenario asserts; null means not asserted. {@code turnStatus} unset means the turn must
   * not end FALLBACK or ERROR. The fields from {@code bookingCount} on are the story's, unread by
   * the turn runner.
   */
  record Expect(
      LeadIntent leadIntent,
      Stage stage,
      List<Flag> flags,
      EndReason endReason,
      HandoffReason handoffReason,
      Boolean goalMet,
      Boolean unsubscribed,
      Boolean conversationEnded,
      Boolean needsHuman,
      Boolean modelCalled,
      Boolean replySent,
      List<String> replyMatches,
      List<String> replyNotMatches,
      Boolean endsWithQuestion,
      List<String> toolCalled,
      TurnStatus turnStatus,
      Integer bookingCount,
      List<String> transcriptMatches,
      EveryReply everyReply,
      Integer retriesMax,
      Integer repliesAfterGoalMax,
      Integer repliesAfterEndMax) {

    static final Expect NONE =
        new Expect(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);
  }

  /** Story rules applied to each agent reply. */
  record EveryReply(
      Integer maxChars,
      List<String> replyNotMatches,
      Boolean oneQuestionMax,
      Boolean endsWithQuestionUntilGoal) {}
}
