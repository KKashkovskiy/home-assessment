package com.enrola.anna.eval;

import static com.enrola.anna.eval.ScenarioFixtures.expect;
import static com.enrola.anna.eval.ScenarioFixtures.turn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enrola.anna.agent.InputItem;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.ModelRequest;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.scheduling.Slot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The offline model answers from the expect block and refuses what it cannot satisfy. */
class StandInModelTest {

  static final Slot MONDAY_NOON =
      new Slot("s1", Instant.parse("2026-07-27T02:00:00Z"), "Mon 27 Jul 12:00 PM AEST");

  @Test
  void replySatisfiesEachRegexAndAsksOneQuestion() {
    var model =
        new StandInModel(
            turn("turn-x", "monday?", expect().matches("\\d{1,2}:\\d{2}", "(?i)\\bAI\\b").build()),
            offered());

    String reply = decided(model.call(request(new InputItem.UserText("context")))).reply();

    assertThat(reply).contains("12:00", "AI").endsWith("?").hasSizeLessThan(300);
    assertThat(reply.chars().filter(c -> c == '?')).hasSize(1);
  }

  @Test
  void closingReplyAsksNothing() {
    var model =
        new StandInModel(
            turn("turn-x", "bye", expect().conversationEnded(true).build()), new Conversation());

    assertThat(decided(model.call(request(new InputItem.UserText("context")))).reply())
        .doesNotContain("?");
  }

  @Test
  void unsupportedRegexThrows() {
    assertThatThrownBy(() -> StandInModel.sample("(?=lookahead)", null))
        .isInstanceOf(ScenarioException.class)
        .hasMessageContaining("lookahead");
  }

  @Test
  void callsTheExpectedToolBeforeDeciding() {
    var model =
        new StandInModel(
            turn("turn-x", "tomorrow morning", expect().tools("get_availability").build()),
            new Conversation());

    InputItem.FunctionCall call =
        ((ModelResponse.ToolCalls) model.call(request(new InputItem.UserText("context"))))
            .calls()
            .getFirst();
    ModelResponse second =
        model.call(
            request(
                new InputItem.UserText("context"),
                call,
                new InputItem.FunctionOutput(
                    call.callId(),
                    "{\"slots\":[{\"slot_id\":\"s1\",\"local_label\":\"Mon 27 Jul 9:00 AM AEST\"}]}")));

    assertThat(call.name()).isEqualTo("get_availability");
    assertThat(call.argumentsJson())
        .contains("\"date_from\":\"2026-07-27\"", "\"date_to\":\"2026-07-27\"", "MORNING");
    assertThat(decided(second).sendReply()).isTrue();
  }

  @Test
  void booksTheFirstSeededSlot() {
    var model =
        new StandInModel(turn("turn-x", "12", expect().tools("book_call").build()), offered());

    ModelResponse response = model.call(request(new InputItem.UserText("context")));

    assertThat(((ModelResponse.ToolCalls) response).calls().getFirst().argumentsJson())
        .isEqualTo("{\"slot_id\":\"s1\"}");
  }

  @Test
  void decidesTheExpectedIntent() {
    var model =
        new StandInModel(
            turn("turn-x", "who are you", expect().intent(LeadIntent.AI_QUESTION).build()),
            new Conversation());

    assertThat(decided(model.call(request(new InputItem.UserText("context")))).leadIntent())
        .isEqualTo(LeadIntent.AI_QUESTION);
  }

  private static Conversation offered() {
    var conversation = new Conversation();
    conversation.setLastOfferedSlots(List.of(MONDAY_NOON));
    return conversation;
  }

  private static com.enrola.anna.agent.Decision decided(ModelResponse response) {
    return ((ModelResponse.Decided) response).decision();
  }

  private static ModelRequest request(InputItem... items) {
    return new ModelRequest(
        "gpt-5.1", "i", List.of(items), "decision", null, List.of(), null, null, 100);
  }
}
