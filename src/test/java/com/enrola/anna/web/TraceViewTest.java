package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.Decisions;
import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.ScriptedModelClient;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.ConversationRepository;
import com.enrola.anna.conversation.InboundTurns;
import com.enrola.anna.conversation.ModelRoundPayload.Call;
import com.enrola.anna.conversation.ModelRoundPayload.Status;
import com.enrola.anna.conversation.TraceOutcome;
import com.enrola.anna.conversation.TurnStatus;
import com.enrola.anna.scheduling.SchedulingTools;
import com.enrola.anna.scheduling.ToolResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * The inspector's view of rows the real pipeline wrote on the scripted model, so a payload that
 * drifts from what the view reads fails here rather than rendering an empty row. Lauren is offered
 * Monday's midday slots, then books noon.
 */
@Import(ConsoleFixture.Scripted.class)
class TraceViewTest extends ConsoleFixture {

  static final String MIDDAY =
      "{\"date_from\":\"2026-07-27\",\"date_to\":\"2026-07-27\",\"part_of_day\":\"MIDDAY\"}";
  static final String OFFER = "I've got Monday at 11:00 AM, 11:30 AM or 12:00 PM. Which suits?";
  static final String CONFIRMATION =
      "Booked, you're in for Mon 27 Jul at 12:00 PM AEST. Your advisor will call you then.";
  static final String QUESTION = "Got it. Is that hospital, extras or both?";
  static final String TOO_LONG = "x".repeat(399) + "?";
  static final String PHONE = "0412 000 111";

  @Autowired ScriptedModelClient model;
  @Autowired InboundTurns inbound;
  @Autowired ConversationRepository rows;
  @Autowired TraceView traces;

  String id;

  @BeforeEach
  void laurenStarted() {
    model.reset();
    id = start("lauren");
  }

  @Test
  void bookingTurnsHaveModelToolGuardAndDiffRows() {
    model.decide(Decisions.engaged().reply(QUESTION).build());
    send("save money");
    model
        .callTool("get_availability", MIDDAY)
        .decide(Decisions.engaged().stage(Stage.OFFER_TIMES).reply(OFFER).build());
    send("monday lunchtime?");
    model
        .callTool("book_call", "{\"slot_id\":\"" + noonSlotId() + "\"}")
        .decide(Decisions.engaged().goalMet().stage(Stage.BOOKED).reply(CONFIRMATION).build());
    send("12 works");

    List<TurnView> turns = traces.turns(id, true);

    assertThat(turns).extracting(TurnView::n).containsExactly(1, 2, 3);
    TurnView offer = turns.get(1);
    assertThat(offer.status()).isEqualTo(TurnStatus.OK);
    assertThat(offer.tokens()).isEqualTo(2 * 1100);
    assertThat(offer.context()).contains("Now: Sunday 26 July 2026", "given_name: Lauren");
    assertThat(offer.promptVersion()).isEqualTo("v1");
    assertThat(offer.steps())
        .hasExactlyElementsOfTypes(
            TurnView.ModelRow.class, TurnView.ToolRow.class, TurnView.ModelRow.class);
    assertThat(models(offer))
        .extracting(TurnView.ModelRow::status, TurnView.ModelRow::round, TurnView.ModelRow::call)
        .containsExactly(
            tuple(Status.TOOL_CALLS, 1, Call.DECIDE), tuple(Status.DECIDED, 2, Call.DECIDE));
    assertThat(models(offer).getFirst().tokens()).isEqualTo(Decisions.USAGE);
    TurnView.ToolRow availability = tools(offer).getFirst();
    assertThat(availability.name()).isEqualTo("get_availability");
    assertThat(availability.answered()).isTrue();
    assertThat(availability.arguments())
        .isEqualTo("date_from = 2026-07-27\ndate_to = 2026-07-27\npart_of_day = MIDDAY");
    assertThat(availability.result()).endsWith(" slots");
    assertThat(availability.filtering()).isEqualTo(new ToolResult.Filtering(22, 16, 0, 0));
    assertThat(availability.slots())
        .extracting(SchedulingTools.OfferedSlot::localLabel)
        .contains("Mon 27 Jul 12:00 PM AEST");
    assertThat(offer.preGuards())
        .extracting(TurnView.GuardRow::name, TurnView.GuardRow::outcome)
        .containsExactly(
            tuple("moderation", TraceOutcome.PASS), tuple("pre-guards", TraceOutcome.PASS));
    assertThat(offer.postGuards())
        .filteredOn(guard -> guard.name().equals("length"))
        .extracting(TurnView.GuardRow::outcome, TurnView.GuardRow::detail)
        .containsExactly(tuple(TraceOutcome.PASS, "chars: " + OFFER.length() + ", limit: 320"));
    int lastRound = models(offer).getLast().seq();
    assertThat(offer.postGuards()).allMatch(guard -> guard.seq() > lastRound);
    assertThat(offer.decision().stage()).isEqualTo(Stage.OFFER_TIMES);
    assertThat(offer.decision().replyFate()).isEqualTo(TurnView.ReplyFate.SENT);
    assertThat(offer.diff()).contains(new TurnView.DiffRow("stage", "ENGAGE", "OFFER_TIMES", null));

    TurnView booked = turns.get(2);
    assertThat(tools(booked)).extracting(TurnView.ToolRow::name).containsExactly("book_call");
    assertThat(tools(booked).getFirst().result()).startsWith("booked Mon 27 Jul 12:00 PM");
    assertThat(tools(booked).getFirst().filtering()).isNull();
    assertThat(booked.diff()).contains(new TurnView.DiffRow("goal_met", "false", "true", null));
    assertThat(booked.codeKept("goal_met")).isNull();
  }

  @Test
  void overridesAreStruckFromTheirGuardEventsAndTheDecisionSaysWhatCodeKept() {
    model.decide(
        Decisions.engaged()
            .goalMet()
            .stage(Stage.BOOKED)
            .ends(EndReason.BOOKED)
            .reply(QUESTION)
            .build());
    send("book me in");

    TurnView turn = traces.turns(id, true).getFirst();

    assertThat(turn.diff())
        .contains(
            new TurnView.DiffRow("goal_met", "false", "false", "true"),
            new TurnView.DiffRow("stage", "ENGAGE", "OFFER_TIMES", "BOOKED"),
            new TurnView.DiffRow("end_reason", "NONE", "NONE", "BOOKED"));
    assertThat(turn.codeKept("end_reason")).isEqualTo("NONE");
    assertThat(turn.codeKept("stage")).isEqualTo("OFFER_TIMES");
    assertThat(turn.postGuards())
        .filteredOn(guard -> guard.outcome() == TraceOutcome.OVERRIDE)
        .extracting(TurnView.GuardRow::name, TurnView.GuardRow::detail)
        .contains(tuple("end_reason", "code: NONE, model: BOOKED"));
  }

  @Test
  void decidedRoundCarriesItsReasoningSummary() {
    model.enqueue(
        Decisions.decided(
            Decisions.engaged().reply(QUESTION).build(), "Lead wants to save; ask cover type."));
    send("save money");

    TurnView.ModelRow round = models(traces.turns(id, true).getFirst()).getFirst();

    assertThat(round.decided()).isTrue();
    assertThat(round.summary()).isEqualTo("Lead wants to save; ask cover type.");
  }

  @Test
  void stopTurnHasPreGuardsOnly() {
    send("STOP");

    TurnView turn = traces.turns(id, true).getFirst();

    assertThat(turn.calledModel()).isFalse();
    assertThat(turn.steps()).isEmpty();
    assertThat(turn.decision()).isNull();
    assertThat(turn.postGuards()).isEmpty();
    assertThat(turn.providerMessageId()).isNull();
    assertThat(turn.preGuards())
        .extracting(TurnView.GuardRow::outcome, TurnView.GuardRow::detail)
        .containsExactly(
            tuple(TraceOutcome.INFO, "skipped: STOP"),
            tuple(TraceOutcome.INFO, "outcome: Unsubscribe, reason: KEYWORD"));
    assertThat(turn.diff()).contains(new TurnView.DiffRow("ended", "false", "true", null));
  }

  @Test
  void failedCallHasItsRoundAndAnErrorWithItsAttemptsButNoDecision() {
    model.enqueue(Decisions.failed(ModelResponse.Failure.TIMEOUT));
    send("hello?");

    TurnView turn = traces.turns(id, true).getFirst();

    assertThat(turn.status()).isEqualTo(TurnStatus.ERROR);
    assertThat(turn.calledModel()).isTrue();
    assertThat(turn.decision()).isNull();
    assertThat(turn.rawDecision()).isNull();
    assertThat(turn.steps())
        .hasExactlyElementsOfTypes(TurnView.ModelRow.class, TurnView.ErrorRow.class);
    TurnView.ModelRow round = models(turn).getFirst();
    assertThat(round.status()).isEqualTo(Status.FAILED);
    assertThat(round.failure()).isEqualTo(ModelResponse.Failure.TIMEOUT);
    assertThat(round.usageReturned()).isFalse();
    assertThat(turn.steps().getLast())
        .isEqualTo(
            new TurnView.ErrorRow(models(turn).getFirst().seq() + 1, "TIMEOUT", "scripted", 3));
  }

  /** The first reply's checks sit between the decision and the repair, in the order they ran. */
  @Test
  void repairTurnReadsInOrder() {
    model
        .decide(Decisions.engaged().reply(TOO_LONG).build())
        .decide(Decisions.engaged().reply(QUESTION).build());
    send("save money");

    TurnView turn = traces.turns(id, true).getFirst();

    assertThat(turn.status()).isEqualTo(TurnStatus.REPAIRED);
    assertThat(turn.steps())
        .hasExactlyElementsOfTypes(
            TurnView.ModelRow.class, TurnView.Checks.class, TurnView.ModelRow.class);
    assertThat(models(turn))
        .extracting(TurnView.ModelRow::call)
        .containsExactly(Call.DECIDE, Call.REPAIR);
    assertThat(((TurnView.Checks) turn.steps().get(1)).rows())
        .filteredOn(guard -> guard.name().equals("length"))
        .extracting(TurnView.GuardRow::outcome)
        .containsExactly(TraceOutcome.REPAIR);
    assertThat(turn.postGuards())
        .filteredOn(guard -> guard.name().equals("length"))
        .extracting(TurnView.GuardRow::outcome)
        .containsExactly(TraceOutcome.PASS);
    assertThat(turn.decision().reply()).isEqualTo(TOO_LONG);
    assertThat(turn.decision().replyFate()).isEqualTo(TurnView.ReplyFate.REWRITTEN);
    assertThat(turn.decision().replyStruck()).isTrue();
  }

  @Test
  void modelOptOutReplyIsReplacedByTheTemplate() {
    model.decide(Decisions.engaged().intent(LeadIntent.OPT_OUT).reply("Sure, bye.").build());
    send("please don't text me again");

    TurnView turn = traces.turns(id, true).getFirst();

    assertThat(turn.decision().replyFate()).isEqualTo(TurnView.ReplyFate.REPLACED);
    assertThat(turn.postGuards())
        .extracting(TurnView.GuardRow::name, TurnView.GuardRow::outcome)
        .contains(tuple("reply-discarded", TraceOutcome.OVERRIDE));
    assertThat(turn.diff()).extracting(TurnView.DiffRow::field).doesNotContain("reply-discarded");
  }

  /** Every text the lead or the model wrote is masked, and an ISO date in it is no phone. */
  @Test
  void maskCoversInboundModelTextToolArgumentsAndRawJson() {
    model
        .callTool(
            "get_availability",
            "{\"date_from\":\"2026-07-27\",\"date_to\":\"2026-07-27\",\"part_of_day\":\"call "
                + PHONE
                + "\"}")
        .enqueue(
            Decisions.decided(
                Decisions.engaged().summary("Callback on " + PHONE).reply(QUESTION).build(),
                "Lead gave " + PHONE));
    send("my number is " + PHONE);

    TurnView masked = traces.turns(id, true).getFirst();
    TurnView clear = traces.turns(id, false).getFirst();

    assertThat(
            List.of(
                masked.inbound(),
                tools(masked).getFirst().arguments(),
                models(masked).getLast().summary(),
                masked.decision().summary(),
                masked.rawDecision()))
        .noneMatch(text -> text.contains(PHONE));
    assertThat(tools(masked).getFirst().arguments()).contains("date_from = 2026-07-27", "111");
    assertThat(clear.inbound()).isEqualTo("my number is " + PHONE);
    assertThat(tools(clear).getFirst().arguments()).contains(PHONE);
    assertThat(clear.rawDecision()).contains(PHONE);
  }

  @Test
  void maskHidesTheHealthTurnWhole() {
    model
        .callTool("get_availability", "{\"date_from\":\"diabetes, call " + PHONE + "\"}")
        .enqueue(
            Decisions.decided(
                Decisions.engaged()
                    .flags(Flag.HEALTH_INFO)
                    .extracted(
                        new Decision.Extracted(
                            Decision.CoverageGoal.SAVE,
                            Decision.CoverType.HOSPITAL,
                            "diabetic, on Bupa",
                            null,
                            Decision.TimeOfDay.UNKNOWN))
                    .summary("Lead has diabetes")
                    .reply(QUESTION)
                    .build(),
                "The lead mentioned diabetes."));
    send("I was just diagnosed with diabetes");

    TurnView turn = traces.turns(id, true).getFirst();

    assertThat(
            List.of(
                turn.inbound(),
                tools(turn).getFirst().arguments(),
                models(turn).getLast().summary(),
                turn.decision().extracted(),
                turn.decision().summary(),
                turn.decision().reply()))
        .containsOnly(PiiMask.HIDDEN);
    assertThat(turn.inboundHidden()).isTrue();
    assertThat(turn.rawDecision()).contains(PiiMask.HIDDEN).doesNotContain("diabet");
    assertThat(traces.raw(id, 1, true))
        .hasValueSatisfying(raw -> assertThat(raw).doesNotContain("diabet"));
  }

  @Test
  void turnWithoutTraceEventsSaysSo() {
    send("STOP");
    jdbc.update("delete from trace_event where conversation_id = ?", id);

    TurnView turn = traces.turns(id, true).getFirst();

    assertThat(turn.steps())
        .containsExactly(
            new TurnView.ErrorRow(null, "NO_TRACE", "no trace events were stored", null));
    assertThat(turn.diff()).isEmpty();
  }

  @Test
  void decisionThisBuildNoLongerReadsKeepsItsRawJson() {
    model.decide(Decisions.engaged().reply(QUESTION).build());
    send("save money");
    jdbc.update(
        "update turn set decision_json = '{\"lead_intent\":\"NOT_IN_THIS_BUILD\"}'"
            + " where conversation_id = ?",
        id);

    TurnView turn = traces.turns(id, true).getFirst();

    assertThat(turn.decision()).isNull();
    assertThat(turn.decisionUnreadable()).isTrue();
    assertThat(turn.rawDecision()).contains("NOT_IN_THIS_BUILD");
    assertThat(turn.steps().getLast())
        .isEqualTo(
            new TurnView.ErrorRow(null, "SCHEMA", "decision schema differs from this build", null));
  }

  /** A round written before this build recorded its call reads as a gap, not a broken page. */
  @Test
  void payloadThisBuildDoesNotReadIsAnUnreadableRow() {
    model.decide(Decisions.engaged().reply(QUESTION).build());
    send("save money");
    jdbc.update(
        "update trace_event set payload_json = '{\"status\":\"TIMEOUT\"}'"
            + " where conversation_id = ? and type = 'MODEL_CALL'",
        id);

    TurnView turn = traces.turns(id, true).getFirst();

    assertThat(turn.steps().getFirst())
        .isInstanceOfSatisfying(
            TurnView.ErrorRow.class, row -> assertThat(row.code()).isEqualTo("UNREADABLE"));
  }

  private void send(String text) {
    inbound.handle(id, text, null);
  }

  private String noonSlotId() {
    return rows.require(id).lastOfferedSlots().stream()
        .filter(slot -> slot.label().equals("Mon 27 Jul 12:00 PM AEST"))
        .findFirst()
        .orElseThrow()
        .slotId();
  }

  private static List<TurnView.ModelRow> models(TurnView turn) {
    return steps(turn, TurnView.ModelRow.class);
  }

  private static List<TurnView.ToolRow> tools(TurnView turn) {
    return steps(turn, TurnView.ToolRow.class);
  }

  private static <T extends TurnView.Step> List<T> steps(TurnView turn, Class<T> type) {
    return turn.steps().stream().filter(type::isInstance).map(type::cast).toList();
  }
}
