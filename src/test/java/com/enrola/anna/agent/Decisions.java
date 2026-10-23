package com.enrola.anna.agent;

import com.enrola.anna.agent.ModelResponse.Usage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseReasoningItem;
import java.util.List;

/** Decisions and responses for tests: an engaged, open decision unless a test says otherwise. */
public final class Decisions {

  /** What every scripted successful call reports using. */
  public static final Usage USAGE = new Usage(1000, 0, 100, 0);

  private Decisions() {}

  public static Builder engaged() {
    return new Builder();
  }

  public static Decision.Extracted extracted(
      Decision.CoverageGoal coverageGoal, Decision.CoverType coverType) {
    return new Decision.Extracted(coverageGoal, coverType, null, null, Decision.TimeOfDay.UNKNOWN);
  }

  /** {@code decision} as a successful round's output, the way the client returns it. */
  public static ModelResponse.Decided decided(Decision decision) {
    return new ModelResponse.Decided(
        ModelResponse.Decided.JSON.valueToTree(decision), null, USAGE, 5, "{}", "{}");
  }

  public static ModelResponse.Failed failed(ModelResponse.Failure code) {
    return new ModelResponse.Failed(code, "scripted", Usage.ZERO, 5, "{}", null);
  }

  /** The reasoning the scripted model replays before each of its function calls. */
  public static final InputItem.Opaque REASONING =
      new InputItem.Opaque(
          ResponseInputItem.ofReasoning(
              ResponseReasoningItem.builder().id("rs-scripted").summary(List.of()).build()));

  /** A round in which the model reasons, then calls the tool {@code name}. */
  public static ModelResponse.ToolCalls toolCall(String name, String argumentsJson) {
    InputItem.FunctionCall call = new InputItem.FunctionCall("call-" + name, name, argumentsJson);
    return new ModelResponse.ToolCalls(List.of(REASONING, call), USAGE, 5, "{}", "{}");
  }

  /** Mutable, test scope only. */
  public static final class Builder {
    private LeadIntent leadIntent = LeadIntent.ENGAGED;
    private ObjectionType objectionType = ObjectionType.NONE;
    private Stage stage = Stage.ENGAGE;
    private List<Flag> flags = List.of();
    private Decision.Extracted extracted =
        Decisions.extracted(Decision.CoverageGoal.UNKNOWN, Decision.CoverType.UNKNOWN);
    private String summary;
    private boolean sendReply = true;
    private String reply = "Got it. What's most important for you to have covered?";
    private boolean goalMet;
    private EndReason endReason = EndReason.NONE;
    private HandoffReason handoffReason = HandoffReason.NONE;

    public Builder intent(LeadIntent value) {
      leadIntent = value;
      return this;
    }

    /** An OBJECTION of {@code value}. */
    public Builder objection(ObjectionType value) {
      leadIntent = LeadIntent.OBJECTION;
      objectionType = value;
      return this;
    }

    public Builder stage(Stage value) {
      stage = value;
      return this;
    }

    public Builder flags(Flag... values) {
      flags = List.of(values);
      return this;
    }

    public Builder extracted(Decision.Extracted value) {
      extracted = value;
      return this;
    }

    public Builder summary(String value) {
      summary = value;
      return this;
    }

    public Builder reply(String value) {
      reply = value;
      return this;
    }

    public Builder noReply() {
      sendReply = false;
      reply = "";
      return this;
    }

    public Builder goalMet() {
      goalMet = true;
      return this;
    }

    public Builder ends(EndReason value) {
      endReason = value;
      return this;
    }

    public Builder handsOff(HandoffReason value) {
      handoffReason = value;
      return this;
    }

    public Decision build() {
      return new Decision(
          leadIntent,
          stage,
          objectionType,
          flags,
          extracted,
          summary,
          sendReply,
          reply,
          goalMet,
          endReason == EndReason.UNSUBSCRIBED,
          endReason != EndReason.NONE,
          endReason,
          handoffReason != HandoffReason.NONE,
          handoffReason);
    }
  }
}
