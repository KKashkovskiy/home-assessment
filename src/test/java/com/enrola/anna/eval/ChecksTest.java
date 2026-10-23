package com.enrola.anna.eval;

import static com.enrola.anna.eval.ScenarioFixtures.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.enrola.anna.agent.Decisions;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.conversation.ConversationStatus;
import com.enrola.anna.conversation.Message;
import com.enrola.anna.conversation.MessageSource;
import com.enrola.anna.conversation.TraceEvent;
import com.enrola.anna.conversation.TraceEventType;
import com.enrola.anna.conversation.TraceOutcome;
import com.enrola.anna.conversation.Turn;
import com.enrola.anna.conversation.TurnStatus;
import com.enrola.anna.eval.Scenario.Expect;
import com.enrola.anna.guard.ReplyContext;
import com.enrola.anna.lead.Lead;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The checks on rows built in memory: one per expectation set, each with the value seen. */
class ChecksTest {

  static final Lead LAUREN =
      new Lead(
          "lauren",
          "Lauren",
          "+61400000001",
          "lauren@example.com",
          "VIC",
          "Bupa",
          null,
          null,
          null,
          Instant.EPOCH);

  @Test
  void flagsRequireEveryListed() {
    List<Check> checks =
        Checks.forTurn(
            expect().flags(Flag.OFF_TOPIC, Flag.OBJECTION).build(),
            rows(conversation(Flag.OFF_TOPIC), List.of(), List.of()));

    assertThat(checks)
        .extracting(Check::name, Check::pass, Check::evidence, Check::turn)
        .containsExactly(tuple("flags", false, "flags=[OFF_TOPIC]", 3));
  }

  @Test
  void toolCalledIsTheExactSet() {
    Expect both = expect().tools("book_call", "get_availability").build();
    TraceEvent availability = event(TraceEventType.TOOL_CALL, "get_availability");
    TraceEvent booking = event(TraceEventType.TOOL_CALL, "book_call");

    assertThat(passes(both, List.of(availability))).containsExactly(false);
    assertThat(passes(both, List.of(availability, booking))).containsExactly(true);
  }

  @Test
  void emptyToolCalledMeansNone() {
    assertThat(
            passes(expect().tools().build(), List.of(event(TraceEventType.TOOL_CALL, "book_call"))))
        .containsExactly(false);
  }

  @Test
  void modelCalledReadsTheTrace() {
    Expect noModel = expect().modelCalled(false).build();

    assertThat(passes(noModel, List.of(event(TraceEventType.GUARD, "stop")))).containsExactly(true);
    assertThat(passes(noModel, List.of(event(TraceEventType.MODEL_CALL, "decide"))))
        .containsExactly(false);
  }

  @Test
  void endsWithQuestionFalseFailsOnAQuestion() {
    List<Check> checks =
        Checks.forTurn(
            expect().endsWithQuestion(false).build(),
            rows(conversation(), List.of(), List.of(reply("Sure?"))));

    assertThat(checks).extracting(Check::pass).containsExactly(false);
  }

  @Test
  void replyMatchesAndIntentReadFromTheDecision() {
    List<Check> checks =
        Checks.forTurn(
            expect().intent(LeadIntent.OTHER).matches("(?i)english").build(),
            new TurnRows(
                conversation(),
                3,
                turn(TurnStatus.OK, LeadIntent.OTHER),
                List.of(event(TraceEventType.MODEL_CALL, "decide")),
                List.of(reply("I can only reply in English")),
                context()));

    assertThat(checks)
        .extracting(Check::name, Check::pass, Check::evidence)
        .containsExactly(
            tuple("lead_intent", true, "expected=OTHER seen=OTHER"),
            tuple("reply_matches (?i)english", true, "\"I can only reply in English\""));
  }

  /** A hit on a forbidden regex fails; a miss, or no reply at all, passes. */
  @Test
  void replyNotMatchesFailsOnlyOnAHit() {
    Expect noDollars = expect().notMatches("\\$\\s?\\d").build();

    assertThat(passesWith(noDollars, reply("It's $30 a month, ok?"))).containsExactly(false);
    assertThat(passesWith(noDollars, reply("An advisor talks price. Ok?"))).containsExactly(true);
    assertThat(
            Checks.forTurn(noDollars, rows(conversation(), List.of(), List.of())).stream()
                .map(Check::pass))
        .containsExactly(true);
  }

  /** The scenario asked for a classification no model made, and the check says so. */
  @Test
  void decisionFieldWithoutADecisionIsAScenarioFault() {
    List<Check> checks =
        Checks.forTurn(
            expect().intent(LeadIntent.ABUSIVE).build(),
            rows(conversation(), List.of(event(TraceEventType.GUARD, "moderation")), List.of()));

    assertThat(checks)
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.pass()).isFalse();
              assertThat(check.evidence()).startsWith("scenario fault");
            });
  }

  @Test
  void stateIsReadFromTheConversation() {
    Conversation ended = conversation();
    ended.ended = true;
    ended.endReason = EndReason.OFF_TOPIC_LIMIT;

    List<Check> checks =
        Checks.forTurn(
            expect().endReason(EndReason.OFF_TOPIC_LIMIT).conversationEnded(true).build(),
            rows(ended, List.of(), List.of()));

    assertThat(checks)
        .extracting(Check::name, Check::pass)
        .containsExactly(tuple("end_reason", true), tuple("conversation_ended", true));
  }

  @Test
  void nothingExpectedNothingChecked() {
    assertThat(Checks.forTurn(Expect.NONE, rows(conversation(), List.of(), List.of(reply("Hi?")))))
        .isEmpty();
  }

  /** The production guards name what fails; a template reply is not held to the question rule. */
  @Test
  void suiteWideRunsTheReplyGuards() {
    List<Check> checks =
        Checks.suiteWide(
            Expect.NONE,
            rows(
                conversation(),
                List.of(),
                List.of(reply("See bit.ly/x, you'll save hundreds. Call 0400 000 001."))));

    assertThat(checks)
        .filteredOn(check -> !check.pass())
        .extracting(Check::name)
        .containsExactly("url", "pii", "deny");
    assertThat(checks).allMatch(check -> check.gate() == Check.Gate.HARD && check.turn() == 3);
  }

  /** A turn that fell back or failed fails unless the scenario expects exactly that. */
  @Test
  void fallbackFailsUnlessExpected() {
    TurnRows fellBack =
        new TurnRows(
            conversation(),
            3,
            turn(TurnStatus.FALLBACK, LeadIntent.ENGAGED),
            List.of(),
            List.of(),
            context());

    assertThat(Checks.suiteWide(Expect.NONE, fellBack))
        .extracting(Check::name, Check::pass, Check::evidence)
        .contains(tuple("turn_status", false, "status=FALLBACK"));
    Expect fallback = expect().turnStatus(TurnStatus.FALLBACK).build();
    assertThat(Checks.suiteWide(fallback, fellBack))
        .extracting(Check::name)
        .doesNotContain("turn_status");
    assertThat(Checks.forTurn(fallback, fellBack))
        .extracting(Check::name, Check::gate, Check::pass)
        .containsExactly(tuple("turn_status", Check.Gate.EXPECTATION, true));
  }

  @Test
  void aSecondClosingReplyFails() {
    Conversation closed = conversation();
    closed.ended = true;
    closed.postTerminalReplies = 2;

    assertThat(Checks.suiteWide(Expect.NONE, rows(closed, List.of(), List.of())))
        .extracting(Check::name, Check::pass)
        .contains(tuple("reply_after_end", false));
  }

  static Conversation conversation(Flag... flags) {
    var conversation = new Conversation();
    conversation.id = "c-1";
    conversation.leadId = "lauren";
    conversation.status = ConversationStatus.ACTIVE;
    conversation.stage = Stage.ENGAGE;
    conversation.flagsJson = "[]";
    conversation.endReason = EndReason.NONE;
    conversation.endReasonHistoryJson = "[]";
    conversation.handoffReason = HandoffReason.NONE;
    for (Flag flag : flags) {
      conversation.addFlag(flag);
    }
    return conversation;
  }

  /** Turn 3 of {@code conversation}, with no turn row. */
  private static TurnRows rows(
      Conversation conversation, List<TraceEvent> events, List<Message> outbound) {
    return new TurnRows(conversation, 3, null, events, outbound, context());
  }

  private static ReplyContext context() {
    return new ReplyContext(LAUREN, "canary-token", List.of(), null, false, false, 320);
  }

  private static List<Boolean> passes(Expect expect, List<TraceEvent> events) {
    return Checks.forTurn(expect, rows(conversation(), events, List.of())).stream()
        .map(Check::pass)
        .toList();
  }

  private static List<Boolean> passesWith(Expect expect, Message reply) {
    return Checks.forTurn(expect, rows(conversation(), List.of(), List.of(reply))).stream()
        .map(Check::pass)
        .toList();
  }

  private static Turn turn(TurnStatus status, LeadIntent intent) {
    var call =
        new Turn.ModelCall(
            JsonMapper.builder()
                .build()
                .writeValueAsString(Decisions.engaged().intent(intent).build()),
            null,
            "gpt-5.1",
            "v1",
            1,
            0,
            1,
            0,
            BigDecimal.ZERO,
            1,
            null,
            null,
            null);
    return new Turn("t", "c-1", 3, "m", status, "{}", call, Instant.EPOCH);
  }

  private static TraceEvent event(TraceEventType type, String name) {
    return new TraceEvent("e", "c-1", 3, 1, type, name, TraceOutcome.PASS, "{}", Instant.EPOCH);
  }

  private static Message reply(String text) {
    return Message.outbound("c-1", 3, text, MessageSource.LLM, Instant.EPOCH);
  }
}
