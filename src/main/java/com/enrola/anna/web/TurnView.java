package com.enrola.anna.web;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.LeadIntent;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.agent.ObjectionType;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.MessageSource;
import com.enrola.anna.conversation.ModelRoundPayload;
import com.enrola.anna.conversation.TraceOutcome;
import com.enrola.anna.conversation.TurnStatus;
import com.enrola.anna.scheduling.SchedulingTools;
import com.enrola.anna.scheduling.ToolResult;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;

/**
 * One turn in the inspector, as {@link TraceView} read it from the turn row and its trace events:
 * the header's figures on the lead's clock, the inbound, the context the model was sent, the guards
 * before the model call, the model rounds, tool calls, errors and in-between checks in the order
 * they happened, the decision as labelled fields with its raw JSON, the guards after the last
 * round, and what the turn changed. With the PII mask on, every text here is already masked. {@code
 * promptVersion}, {@code context}, {@code decision} and {@code rawDecision} are null when the turn
 * made no model call, the last two also when the call failed; {@code decision} alone is null when
 * the stored decision no longer fits this build's schema.
 */
public record TurnView(
    String conversationId,
    int n,
    ZonedDateTime at,
    TurnStatus status,
    long latencyMs,
    int tokens,
    BigDecimal cost,
    String inbound,
    String providerMessageId,
    String context,
    String promptVersion,
    List<GuardRow> preGuards,
    List<Step> steps,
    DecisionView decision,
    String rawDecision,
    List<GuardRow> postGuards,
    List<DiffRow> diff) {

  /** Whether the turn reached the model, so its request and prompt version were stored. */
  public boolean calledModel() {
    return promptVersion != null;
  }

  /** A stored decision this build can no longer read: its raw JSON shows instead. */
  public boolean decisionUnreadable() {
    return decision == null && rawDecision != null;
  }

  /** The mask hid the inbound whole, so it is a placeholder, not the lead's words. */
  public boolean inboundHidden() {
    return PiiMask.HIDDEN.equals(inbound);
  }

  /**
   * What code kept for {@code field} where it overrode the model's value; null where it did not.
   */
  public String codeKept(String field) {
    return diff.stream()
        .filter(row -> row.field().equals(field) && row.modelSaid() != null)
        .map(DiffRow::after)
        .findFirst()
        .orElse(null);
  }

  /** A row between the guards, rendered by the fragment {@link #fragment()} names. */
  public sealed interface Step permits ModelRow, ToolRow, ErrorRow, Checks {

    String fragment();
  }

  /** A GUARD event: the check, its verdict and what its payload says, in one line. */
  public record GuardRow(int seq, String name, TraceOutcome outcome, String detail) {}

  /**
   * GUARD events between two model calls: the checks of a reply that went back to the model, and
   * the overrides of the decision that wrote it.
   */
  public record Checks(List<GuardRow> rows) implements Step {

    Checks plus(GuardRow row) {
      return new Checks(Stream.concat(rows.stream(), Stream.of(row)).toList());
    }

    @Override
    public String fragment() {
      return "checks";
    }
  }

  /**
   * One model round, numbered across the turn, of the decision call or of its repair. {@code
   * failure} is set only for a failed round, and {@code summary}, the model's reasoning summary,
   * never sent to the lead, only for one that decided and returned it.
   */
  public record ModelRow(
      int seq,
      int round,
      ModelRoundPayload.Call call,
      String model,
      String effort,
      ModelResponse.Usage tokens,
      BigDecimal cost,
      long latencyMs,
      ModelRoundPayload.Status status,
      ModelResponse.Failure failure,
      String summary)
      implements Step {

    public boolean decided() {
      return status == ModelRoundPayload.Status.DECIDED;
    }

    /** A round that failed before any answer reports no usage. */
    public boolean usageReturned() {
      return !tokens.equals(ModelResponse.Usage.ZERO);
    }

    @Override
    public String fragment() {
      return "model";
    }
  }

  /**
   * One function call: its arguments one {@code name = value} to a line, the answer in a line with
   * the hint an error gave the model, how an availability answer's filtering came down to the slots
   * it offered, and whether the calendar or tool answered without an error. {@code hint} and {@code
   * filtering} are null where the answer had none.
   */
  public record ToolRow(
      int seq,
      String name,
      boolean answered,
      String arguments,
      String result,
      String hint,
      ToolResult.Filtering filtering,
      List<SchedulingTools.OfferedSlot> slots,
      long latencyMs)
      implements Step {

    @Override
    public String fragment() {
      return "tool";
    }
  }

  /**
   * An ERROR event, or one the view adds when a turn left no trace or its decision no longer reads;
   * those have no sequence number. {@code maxAttempts} is the attempts a failed model call was
   * allowed, null for any other error.
   */
  public record ErrorRow(Integer seq, String code, String note, Integer maxAttempts)
      implements Step {

    @Override
    public String fragment() {
      return "error";
    }
  }

  /**
   * The model's decision as labelled fields, and what became of its reply: sent as written,
   * rewritten by the repair call, replaced by a fixed template, or not sent.
   */
  public record DecisionView(
      LeadIntent intent,
      Stage stage,
      ObjectionType objection,
      List<Flag> flags,
      String extracted,
      String summary,
      boolean sendReply,
      boolean goalMet,
      boolean unsubscribed,
      boolean ended,
      EndReason endReason,
      boolean needsHuman,
      HandoffReason handoff,
      String reply,
      ReplyFate replyFate) {

    public boolean objected() {
      return objection != ObjectionType.NONE;
    }

    /** The reply is struck through when the lead did not get it as the model wrote it. */
    public boolean replyStruck() {
      return sendReply && replyFate != ReplyFate.SENT;
    }
  }

  /** What the lead got in place of the model's reply. */
  public enum ReplyFate {
    SENT,
    REWRITTEN,
    REPLACED,
    NOT_SENT;

    /**
     * From the turn's status and the source of the message it sent, null when it sent none: a
     * repaired turn sent the rewrite, a fixed source a template, an LLM source the model's words.
     */
    static ReplyFate of(TurnStatus status, MessageSource sent) {
      if (sent == null) {
        return NOT_SENT;
      }
      if (status == TurnStatus.REPAIRED) {
        return REWRITTEN;
      }
      return switch (sent) {
        case LLM -> SENT;
        case FIXED, TEMPLATE -> REPLACED;
      };
    }
  }

  /**
   * One field the turn changed, as the STATE_DIFF event has it; {@code modelSaid} is the value the
   * model asked for and code overrode, null when there was none.
   */
  public record DiffRow(String field, String before, String after, String modelSaid) {}
}
