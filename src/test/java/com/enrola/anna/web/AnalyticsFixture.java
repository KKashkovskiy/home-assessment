package com.enrola.anna.web;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.Decisions;
import com.enrola.anna.agent.InputItem;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.ModelResponse.Usage;
import com.enrola.anna.agent.ObjectionType;
import com.enrola.anna.agent.ScriptedModelClient;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.ConversationRepository;
import com.enrola.anna.conversation.InboundTurns;
import com.enrola.anna.conversation.Source;
import com.enrola.anna.guard.ScriptedModerationClient;
import com.enrola.anna.lead.LeadService;
import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptVersion;
import com.enrola.anna.scheduling.Slot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Six conversations played through the real pipeline on the scripted model, so every figure is
 * known. Each decision's latency and output tokens are scripted, at $10 per million output tokens,
 * so 1000 tokens cost a cent; tool rounds cost nothing and take no time:
 *
 * <ul>
 *   <li>c1 Lauren, v1: engaged, asked for a call, offered Monday, books the first slot ten minutes
 *       after the start. Four model turns of 2000, 2500, 3000 and 1700 ms; $0.04.
 *   <li>c2 John, v1: busy, then engaged; a reply too long, repaired; asked, offered, books twenty
 *       minutes after the start. Six model turns of 1800, 2100, 3900 (1000 and the 2900 ms repair),
 *       2200, 2600 and 2700 ms; $0.06; the long reply fails two checks, length and exclamation, on
 *       that one turn.
 *   <li>c3 Jane, v2: not interested twice at the call ask, which ends DECLINED. Two model turns of
 *       2300 and 2400 ms; $0.03.
 *   <li>c4 Kim, custom: STOP. One turn without the model; suppressed KEYWORD.
 *   <li>c5 Lee, custom: an opt-out in their own words. One model turn of 1900 ms; $0.01; suppressed
 *       MODEL.
 *   <li>c6 Max, custom: the opener only, started forty days before the rest.
 * </ul>
 *
 * Totals: 6 started, 5 replied, 2 booked, 2 opted out, 14 turns of which 13 called the model,
 * $0.14. The clock stands at {@link ConsoleFixture#NOW}, Sunday 26 July 10:00 in Melbourne.
 */
@Import(ConsoleFixture.Scripted.class)
abstract class AnalyticsFixture extends ConsoleFixture {

  static final String QUESTION = "Got it. Is that hospital, extras or both?";
  static final String CALL_ASK = "Thanks. Would a free 15-minute call with an advisor suit?";
  static final String MIDDAY =
      "{\"date_from\":\"2026-07-27\",\"date_to\":\"2026-07-27\",\"part_of_day\":\"MIDDAY\"}";
  static final String OFFER = "I've got a few lunchtime times on Monday. Shall I list them?";

  /** Fails two reply checks, length and exclamation, on one turn. */
  static final String TOO_LONG = "x".repeat(398) + "!?";

  @Autowired ScriptedModelClient model;
  @Autowired ScriptedModerationClient moderation;
  @Autowired InboundTurns inbound;
  @Autowired ConversationRepository rows;
  @Autowired LeadService leads;
  @Autowired PromptStore prompts;
  @Autowired AnalyticsService analytics;

  String c1;
  String c2;
  String c3;
  String c4;
  String c5;
  String c6;

  @BeforeEach
  void sixConversations() {
    model.reset();
    moderation.reset();
    Instant now = clock.now();
    c1 = laurenBooks(now);
    c2 = johnObjectsThenBooks(now);
    c3 = janeDeclinesOnV2();
    c4 = custom("Kim", "0400 000 104");
    inbound.handle(c4, "STOP", null);
    c5 = custom("Lee", "0400 000 105");
    turn(c5, "please don't text me again", Decisions.engaged().intent(LeadIntent.OPT_OUT), 1900);
    clock.fixAt(now.minus(Duration.ofDays(40)));
    c6 = custom("Max", "0400 000 106");
    clock.fixAt(now);
  }

  /** All time, every source, model and version. */
  static AnalyticsFilter allTime() {
    return new AnalyticsFilter(AnalyticsFilter.Range.ALL, null, null, null);
  }

  private String laurenBooks(Instant now) {
    String id = start("lauren");
    turn(id, "save money", Decisions.engaged().reply(QUESTION), 2000);
    turn(id, "hospital mainly", Decisions.engaged().stage(Stage.CALL_ASK).reply(CALL_ASK), 2500);
    offer(id, 3000);
    clock.fixAt(now.plus(Duration.ofMinutes(10)));
    book(id, 1700);
    clock.fixAt(now);
    return id;
  }

  private String johnObjectsThenBooks(Instant now) {
    String id = start("john");
    turn(
        id,
        "not now, busy",
        Decisions.engaged().objection(ObjectionType.BUSY).reply("No worries. Is later better?"),
        1800);
    turn(id, "ok go on", Decisions.engaged().reply(QUESTION), 2100);
    model.enqueue(decided(Decisions.engaged().reply(TOO_LONG).build(), 1000, 500));
    turn(id, "hospital", Decisions.engaged().reply(QUESTION), 2900, 500);
    turn(id, "price mostly", Decisions.engaged().stage(Stage.CALL_ASK).reply(CALL_ASK), 2200);
    offer(id, 2600);
    clock.fixAt(now.plus(Duration.ofMinutes(20)));
    book(id, 2700);
    clock.fixAt(now);
    return id;
  }

  private String janeDeclinesOnV2() {
    PromptVersion v1 = prompts.get("v1");
    String v2 =
        ((PromptStore.SaveResult.Saved)
                prompts.save(
                    new PromptStore.SaveRequest(
                        v1.promptMd() + "\nKeep it brief.\n",
                        v1.knowledgeMd(),
                        v1.schemaJson(),
                        v1.toolsJson(),
                        v1.templatesJson(),
                        "brief")))
            .id();
    prompts.activate(v2);
    String id = start("jane");
    prompts.activate("v1");
    Decisions.Builder notInterested =
        Decisions.engaged()
            .objection(ObjectionType.NOT_INTERESTED)
            .stage(Stage.CALL_ASK)
            .reply("Fair enough. Would a short call still help?");
    turn(id, "not interested", notInterested, 2300, 1500);
    turn(id, "still no", notInterested, 2400, 1500);
    return id;
  }

  /** The availability round, then the offer. */
  private void offer(String id, int latencyMs) {
    model.enqueue(toolCall("get_availability", MIDDAY));
    turn(
        id,
        "monday lunchtime?",
        Decisions.engaged().stage(Stage.OFFER_TIMES).reply(OFFER),
        latencyMs);
  }

  /** Books the first slot offered, through book_call, and confirms it by its label. */
  private void book(String id, int latencyMs) {
    Slot first = rows.require(id).lastOfferedSlots().getFirst();
    model.enqueue(toolCall("book_call", "{\"slot_id\":\"" + first.slotId() + "\"}"));
    String confirmation =
        "Booked, you're in for " + first.label() + ". Your advisor will call then.";
    turn(
        id,
        "the first one",
        Decisions.engaged().goalMet().stage(Stage.BOOKED).reply(confirmation),
        latencyMs);
  }

  private void turn(String id, String text, Decisions.Builder decision, int latencyMs) {
    turn(id, text, decision, latencyMs, 1000);
  }

  private void turn(
      String id, String text, Decisions.Builder decision, int latencyMs, int outputTokens) {
    model.enqueue(decided(decision.build(), latencyMs, outputTokens));
    inbound.handle(id, text, null);
  }

  /** A custom lead in Victoria, started from the console. */
  String custom(String name, String phone) {
    LeadService.Entered lead =
        leads.createCustom(
            new LeadService.CustomLead(name, phone, null, "VIC", null, null, "analytics test"));
    return conversations.start(lead.lead().id(), Source.SIM).conversationId();
  }

  /** A decision that took {@code latencyMs} and wrote {@code outputTokens}, and read nothing. */
  private static ModelResponse decided(Decision decision, int latencyMs, int outputTokens) {
    ModelResponse.Decided scripted = Decisions.decided(decision);
    return new ModelResponse.Decided(
        scripted.output(), null, new Usage(0, 0, outputTokens, 0), latencyMs, "{}", "{}");
  }

  private static ModelResponse toolCall(String name, String argumentsJson) {
    InputItem.FunctionCall call = new InputItem.FunctionCall("call-" + name, name, argumentsJson);
    return new ModelResponse.ToolCalls(
        List.of(Decisions.REASONING, call), Usage.ZERO, 0, "{}", "{}");
  }
}
