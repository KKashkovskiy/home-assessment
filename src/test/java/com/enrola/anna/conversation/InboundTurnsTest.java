package com.enrola.anna.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.Decisions;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.ModelRequest;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.ScriptedModelClient;
import com.enrola.anna.agent.Stage;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lauren's turns through the real pipeline and H2 with the model scripted: the decision applied,
 * the call recorded on the turn and in the trace, a failure handed off.
 */
@SpringBootTest(properties = "OPENAI_API_KEY=")
@Import(InboundTurnsTest.ScriptedModel.class)
class InboundTurnsTest {

  static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void databaseInTempDir(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("anna"));
  }

  @TestConfiguration
  static class ScriptedModel {
    @Bean
    ScriptedModelClient scriptedModel() {
      return new ScriptedModelClient();
    }

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  @Autowired InboundTurns turns;
  @Autowired ConversationService conversations;
  @Autowired ScriptedModelClient model;
  @Autowired JdbcTemplate jdbc;
  String id;

  @BeforeEach
  void startLauren() {
    jdbc.update("delete from trace_event");
    jdbc.update("delete from turn");
    jdbc.update("delete from message");
    jdbc.update("delete from conversation");
    jdbc.update("delete from suppression");
    model.reset();
    id = conversations.start("lauren").conversationId();
  }

  @Test
  void decisionSetsStageFlagsAndReply() {
    String reply = "Makes sense. What's most important for you to have covered?";
    model.decide(
        Decisions.engaged().stage(Stage.PREFERENCE).flags(Flag.OBJECTION).reply(reply).build());

    TurnResponse response = turns.handle(id, "save money", "p1");

    assertThat(response.reply()).isEqualTo(reply);
    assertThat(response.stage()).isEqualTo(Stage.PREFERENCE);
    assertThat(response.flags()).contains(Flag.OBJECTION);
    assertThat(response.needsHuman()).isFalse();
    assertThat(response.transcript().getLast().source()).isEqualTo(MessageSource.LLM);
    assertThat(turnStatus()).isEqualTo("OK");
  }

  @Test
  void callIsRecordedOnTheTurnAndTraced() {
    model.decide(Decisions.engaged().stage(Stage.PREFERENCE).build());

    turns.handle(id, "save money", "p1");

    Map<String, Object> turn =
        jdbc.queryForMap(
            "select model, prompt_version, tokens_in, tokens_out, cost_usd, latency_ms,"
                + " decision_json from turn where conversation_id = ?",
            id);
    assertThat(turn)
        .containsEntry("MODEL", "gpt-5.1")
        .containsEntry("PROMPT_VERSION", "v1")
        .containsEntry("TOKENS_IN", 1000)
        .containsEntry("TOKENS_OUT", 100)
        .containsEntry("COST_USD", new BigDecimal("0.002250"))
        .containsEntry("LATENCY_MS", 5);
    assertThat(turn.get("DECISION_JSON").toString()).contains("\"stage\":\"PREFERENCE\"");
    assertThat(traceTypes()).containsExactly("MODEL_CALL");
  }

  @Test
  void failedCallHandsOffAndStaysOpen() {
    model.enqueue(Decisions.failed(ModelResponse.Failure.TIMEOUT));

    TurnResponse response = turns.handle(id, "save money", "p1");

    assertThat(response.reply()).isNull();
    assertThat(response.needsHuman()).isTrue();
    assertThat(response.handoffReason()).isEqualTo(HandoffReason.ERROR);
    assertThat(response.conversationEnded()).isFalse();
    assertThat(turnStatus()).isEqualTo("ERROR");
    assertThat(traceTypes()).containsExactly("MODEL_CALL", "ERROR");
    assertThat(
            jdbc.queryForObject(
                "select payload_json from trace_event where conversation_id = ? and type = 'ERROR'",
                String.class,
                id))
        .contains("\"code\":\"TIMEOUT\"");
  }

  /** Recorded like any failed call: the raw output stays on the turn for the inspector. */
  @Test
  void outputThatIsNotADecisionHandsOff() {
    ModelResponse.Decided decided = Decisions.decided(Decisions.engaged().build());
    ((ObjectNode) decided.output()).remove("stage");
    model.enqueue(decided);

    TurnResponse response = turns.handle(id, "save money", "p1");

    assertThat(response.reply()).isNull();
    assertThat(response.needsHuman()).isTrue();
    assertThat(response.handoffReason()).isEqualTo(HandoffReason.ERROR);
    assertThat(response.stage()).isEqualTo(Stage.ENGAGE);
    assertThat(turnStatus()).isEqualTo("ERROR");
    assertThat(
            jdbc.queryForList(
                "select payload_json from trace_event where conversation_id = ? order by seq",
                String.class,
                id))
        .satisfiesExactly(
            call -> assertThat(call).contains("\"status\":\"SCHEMA\""),
            error -> assertThat(error).contains("\"code\":\"SCHEMA\""));
  }

  @Test
  void longInboundIsCutForTheModelAndKeptWhole() {
    model.decide(Decisions.engaged().build());

    TurnResponse response = turns.handle(id, "a".repeat(1700), "p1");

    assertThat(model.calls().getFirst().input().get(1).text()).hasSize(1600);
    assertThat(response.transcript().get(1).chars()).isEqualTo(1700);
    assertThat(response.flags()).contains(Flag.INPUT_TRUNCATED);
  }

  @Test
  void decisionWithoutReplySendsNothing() {
    model.decide(Decisions.engaged().noReply().build());

    TurnResponse response = turns.handle(id, "ok", "p1");

    assertThat(response.reply()).isNull();
    assertThat(response.transcript()).hasSize(2);
    assertThat(turnStatus()).isEqualTo("NO_REPLY");
  }

  @Test
  void knownFactsSurviveALaterUnknown() {
    model
        .decide(
            Decisions.engaged()
                .extracted(
                    Decisions.extracted(Decision.CoverageGoal.SAVE, Decision.CoverType.UNKNOWN))
                .build())
        .decide(
            Decisions.engaged()
                .extracted(
                    Decisions.extracted(Decision.CoverageGoal.UNKNOWN, Decision.CoverType.HOSPITAL))
                .build());

    turns.handle(id, "save money", "p1");
    TurnResponse response = turns.handle(id, "hospital mainly", "p2");

    assertThat(response.extracted().get("coverage_goal").stringValue()).isEqualTo("SAVE");
    assertThat(response.extracted().get("cover_type").stringValue()).isEqualTo("HOSPITAL");
    assertThat(response.extracted().has("preferred_time_of_day")).isFalse();
  }

  @Test
  void nullSummaryKeepsTheLastOne() {
    model
        .decide(Decisions.engaged().summary("wants to save").build())
        .decide(Decisions.engaged().summary(null).build());

    turns.handle(id, "save money", "p1");
    turns.handle(id, "yep", "p2");

    assertThat(
            jdbc.queryForObject("select summary from conversation where id = ?", String.class, id))
        .isEqualTo("wants to save");
  }

  @Test
  void modelEndingGoesThroughStateRules() {
    model.decide(Decisions.engaged().ends(EndReason.DECLINED).reply("Ok, understood.").build());

    TurnResponse response = turns.handle(id, "no thanks", "p1");

    assertThat(response.conversationEnded()).isTrue();
    assertThat(response.endReason()).isEqualTo(EndReason.DECLINED);
    assertThat(response.endReasonHistory())
        .extracting(EndReasonEntry::reason)
        .containsExactly(EndReason.DECLINED);
    assertThat(response.reply()).isEqualTo("Ok, understood.");
  }

  @Test
  void modelHandoffAsksForAHumanAndStaysOpen() {
    model.decide(Decisions.engaged().handsOff(HandoffReason.DISTRESS).reply("I hear you.").build());

    TurnResponse response = turns.handle(id, "it's all too much", "p1");

    assertThat(response.needsHuman()).isTrue();
    assertThat(response.handoffReason()).isEqualTo(HandoffReason.DISTRESS);
    assertThat(response.conversationEnded()).isFalse();
  }

  /** The strict schema allows a flag without its reason and the reverse; neither changes state. */
  @ParameterizedTest(name = "{0}={1}, {2}={3}")
  @CsvSource({
    "conversation_ended, true, end_reason, NONE",
    "conversation_ended, false, end_reason, DECLINED",
    "needs_human, true, handoff_reason, NONE",
    "needs_human, false, handoff_reason, REQUESTED"
  })
  void halfAskedEndingOrHandoffChangesNothing(
      String flag, boolean value, String reasonField, String reason) {
    ModelResponse.Decided decided = Decisions.decided(Decisions.engaged().build());
    ((ObjectNode) decided.output()).put(flag, value).put(reasonField, reason);
    model.enqueue(decided);

    TurnResponse response = turns.handle(id, "hmm", "p1");

    assertThat(response.conversationEnded()).isFalse();
    assertThat(response.needsHuman()).isFalse();
  }

  @Test
  void summaryIsCutToItsColumn() {
    model.decide(Decisions.engaged().summary("s".repeat(250)).build());

    turns.handle(id, "save money", "p1");

    assertThat(
            jdbc.queryForObject("select summary from conversation where id = ?", String.class, id))
        .hasSize(200);
  }

  @Test
  void goalMetFromTheModelIsTracedAndIgnored() {
    model.decide(Decisions.engaged().goalMet().build());

    TurnResponse response = turns.handle(id, "sounds good", "p1");

    assertThat(response.goalMet()).isFalse();
    assertThat(
            jdbc.queryForList(
                "select name || ' ' || outcome from trace_event where conversation_id = ?"
                    + " and type = 'GUARD'",
                String.class,
                id))
        .containsExactly("goal_met_override OVERRIDE");
  }

  @Test
  void transcriptIsReplayedFromTheRowsEachTurn() {
    model.decide(Decisions.engaged().build()).decide(Decisions.engaged().reply("Second.").build());
    turns.handle(id, "save money", "p1");

    turns.handle(id, "hospital", "p2");

    List<ModelRequest.InputItem> input = model.calls().getLast().input();
    assertThat(input)
        .hasExactlyElementsOfTypes(
            ModelRequest.AssistantText.class,
            ModelRequest.UserText.class,
            ModelRequest.AssistantText.class,
            ModelRequest.UserText.class,
            ModelRequest.UserText.class);
    assertThat(input.get(1).text()).isEqualTo("save money");
    assertThat(input.getLast().text()).contains("Agent turns used: 1 of");
  }

  /** The scripted model throws when called with nothing queued, so these never reached it. */
  @Test
  void stopNeverReachesTheModel() {
    TurnResponse response = turns.handle(id, "STOP", "p1");

    assertThat(response.unsubscribed()).isTrue();
    assertThat(model.calls()).isEmpty();
  }

  private String turnStatus() {
    return jdbc.queryForObject(
        "select status from turn where conversation_id = ? and turn_no = 1", String.class, id);
  }

  private List<String> traceTypes() {
    return jdbc.queryForList(
        "select type from trace_event where conversation_id = ? order by seq", String.class, id);
  }
}
