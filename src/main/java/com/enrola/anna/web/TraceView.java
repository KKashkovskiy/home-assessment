package com.enrola.anna.web;

import com.enrola.anna.agent.Decision;
import com.enrola.anna.agent.OpenAiModelClient;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.conversation.ConversationRepository;
import com.enrola.anna.conversation.Direction;
import com.enrola.anna.conversation.ErrorPayload;
import com.enrola.anna.conversation.Message;
import com.enrola.anna.conversation.MessageRepository;
import com.enrola.anna.conversation.ModelRoundPayload;
import com.enrola.anna.conversation.OverridePayload;
import com.enrola.anna.conversation.StateChange;
import com.enrola.anna.conversation.ToolCallPayload;
import com.enrola.anna.conversation.TraceEvent;
import com.enrola.anna.conversation.TraceEventRepository;
import com.enrola.anna.conversation.TraceEventType;
import com.enrola.anna.conversation.TraceOutcome;
import com.enrola.anna.conversation.Turn;
import com.enrola.anna.conversation.TurnRepository;
import com.enrola.anna.prompt.PromptVersion;
import com.enrola.anna.prompt.PromptVersionRepository;
import com.enrola.anna.scheduling.SchedulingTools;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Builds the inspector from the stored turn rows and their trace events, reading each payload as
 * the record its writer wrote: templates read {@link TurnView} fields, never JSON. Nothing is
 * recomputed from the conversation; what the pipeline did not record is not shown. Masking happens
 * here, under the thread's rule: phones and emails masked, and from the turn that first carried
 * health information on, the turn's free text and raw JSON hidden whole.
 */
@Component
public class TraceView {

  private static final TypeReference<Map<String, StateChange>> STATE_DIFF =
      new TypeReference<>() {};

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final TurnRepository turns;
  private final TraceEventRepository events;
  private final PromptVersionRepository versions;
  private final JsonMapper json;

  public TraceView(
      ConversationRepository conversations,
      MessageRepository messages,
      TurnRepository turns,
      TraceEventRepository events,
      PromptVersionRepository versions,
      JsonMapper json) {
    this.conversations = conversations;
    this.messages = messages;
    this.turns = turns;
    this.events = events;
    this.versions = versions;
    this.json = json;
  }

  /** How stored text reaches the page, as stored with the mask off. */
  private record Shown(Conversation conversation, boolean mask) {

    /** Text the lead or the model wrote on turn {@code turnNo}, or the model sent a tool. */
    String text(String text, int turnNo) {
      return mask ? PiiMask.freeText(text, conversation.hidesBody(turnNo)) : text;
    }

    /** The context block: lead record fields and slots, never a message body. */
    String context(String context) {
      return mask ? PiiMask.maskText(context) : context;
    }

    /** Raw JSON of turn {@code turnNo}, whose request replays the transcript up to it. */
    JsonNode json(JsonNode node, int turnNo) {
      if (!mask) {
        return node;
      }
      return conversation.hidesBody(turnNo)
          ? StringNode.valueOf(PiiMask.HIDDEN)
          : PiiMask.maskJson(node);
    }
  }

  /** A finished turn with its trace events in order, its inbound and its reply, null for none. */
  private record TurnRows(Turn turn, List<TraceEvent> events, Message inbound, Message reply) {

    int n() {
      return turn.turnNo();
    }
  }

  /** The seq numbers of a turn's first and last model rounds, both past every event without one. */
  private record ModelSpan(int first, int last) {

    static ModelSpan of(List<TraceEvent> events) {
      int[] rounds =
          events.stream()
              .filter(event -> event.type() == TraceEventType.MODEL_CALL)
              .mapToInt(TraceEvent::seq)
              .toArray();
      return rounds.length == 0
          ? new ModelSpan(Integer.MAX_VALUE, Integer.MAX_VALUE)
          : new ModelSpan(rounds[0], rounds[rounds.length - 1]);
    }

    boolean between(int seq) {
      return seq > first && seq < last;
    }
  }

  /** Every finished turn of the conversation, oldest first. */
  public List<TurnView> turns(String conversationId, boolean mask) {
    Shown shown = new Shown(conversations.require(conversationId), mask);
    Map<Integer, List<TraceEvent>> eventsByTurn =
        events.findByConversationIdOrderByTurnNoAscSeqAsc(conversationId).stream()
            .collect(Collectors.groupingBy(TraceEvent::turnNo));
    List<Message> transcript = messages.transcript(conversationId);
    return turns.findByConversationIdOrderByTurnNoAsc(conversationId).stream()
        .map(
            turn ->
                new TurnRows(
                    turn,
                    eventsByTurn.getOrDefault(turn.turnNo(), List.of()),
                    message(transcript, turn.turnNo(), Direction.IN),
                    message(transcript, turn.turnNo(), Direction.OUT)))
        .map(rows -> turnView(rows, shown))
        .toList();
  }

  /** One finished turn; empty while its number is claimed but the turn has not finished. */
  public Optional<TurnView> turn(String conversationId, int turnNo, boolean mask) {
    // ponytail: builds every turn to return one; a conversation is capped at max-turns replies
    return turns(conversationId, mask).stream().filter(turn -> turn.n() == turnNo).findFirst();
  }

  /**
   * The turn's stored decision, request and response as one pretty-printed document, masked like
   * the page; empty when the turn has not finished, with null members where it made no model call.
   */
  public Optional<String> raw(String conversationId, int turnNo, boolean mask) {
    Shown shown = new Shown(conversations.require(conversationId), mask);
    return turns
        .findByConversationIdAndTurnNo(conversationId, turnNo)
        .map(Turn::modelCall)
        .map(
            call -> {
              ObjectNode document = json.createObjectNode();
              document.set("decision", tree(call.decisionJson()));
              document.set("request", tree(call.rawRequestJson()));
              document.set("response", tree(call.rawResponseJson()));
              return pretty(shown.json(document, turnNo));
            });
  }

  /**
   * What the model was told on turn {@code turnNo}: the instructions and the context its request
   * carried, exactly as sent, so a newer active version changes nothing here. Without a turn, or
   * for one that sent no request, the conversation's pinned version as stored.
   */
  public InspectorView.Prompt prompt(String conversationId, Integer turnNo, boolean mask) {
    Shown shown = new Shown(conversations.require(conversationId), mask);
    return Optional.ofNullable(turnNo)
        .flatMap(n -> turns.findByConversationIdAndTurnNo(conversationId, n))
        .map(Turn::modelCall)
        .filter(call -> call.rawRequestJson() != null)
        .map(call -> sentPrompt(call, turnNo, shown))
        .orElseGet(() -> pinnedPrompt(shown.conversation().promptVersion));
  }

  private static InspectorView.Prompt sentPrompt(Turn.ModelCall call, int turnNo, Shown shown) {
    OpenAiModelClient.SentPrompt sent = OpenAiModelClient.sentPrompt(call.rawRequestJson());
    return new InspectorView.Prompt(
        call.promptVersion(), turnNo, sent.instructions(), shown.context(sent.context()));
  }

  private InspectorView.Prompt pinnedPrompt(String version) {
    return new InspectorView.Prompt(
        version, null, versions.findById(version).map(PromptVersion::promptMd).orElse(null), null);
  }

  private TurnView turnView(TurnRows rows, Shown shown) {
    Turn turn = rows.turn();
    Turn.ModelCall call = turn.modelCall();
    ModelSpan span = ModelSpan.of(rows.events());
    JsonNode stored = tree(call.decisionJson());
    Optional<Decision> decision = stored.isNull() ? Optional.empty() : readable(stored);
    List<TurnView.Step> steps = new ArrayList<>(steps(rows, span, shown));
    if (!stored.isNull() && decision.isEmpty()) {
      steps.add(
          new TurnView.ErrorRow(null, "SCHEMA", "decision schema differs from this build", null));
    }
    return new TurnView(
        turn.conversationId(),
        rows.n(),
        turn.createdAt().atZone(ZoneId.of(shown.conversation().leadZoneId)),
        turn.status(),
        call.latencyMs(),
        call.tokensIn() + call.tokensOut(),
        call.costUsd(),
        rows.inbound() == null ? null : shown.text(rows.inbound().text(), rows.n()),
        rows.inbound() == null ? null : rows.inbound().providerMessageId(),
        call.rawRequestJson() == null
            ? null
            : shown.context(OpenAiModelClient.sentPrompt(call.rawRequestJson()).context()),
        call.promptVersion(),
        guards(rows.events(), seq -> seq < span.first()),
        List.copyOf(steps),
        decision.map(read -> decisionView(read, rows, shown)).orElse(null),
        stored.isNull() ? null : pretty(shown.json(stored, rows.n())),
        guards(rows.events(), seq -> seq > span.last()),
        diff(rows.events()));
  }

  private static Message message(List<Message> transcript, int turnNo, Direction direction) {
    return transcript.stream()
        .filter(message -> message.turnNo() == turnNo && message.direction() == direction)
        .findFirst()
        .orElse(null);
  }

  private List<TurnView.GuardRow> guards(List<TraceEvent> events, IntPredicate side) {
    return events.stream()
        .filter(event -> event.type() == TraceEventType.GUARD && side.test(event.seq()))
        .map(this::guardRow)
        .toList();
  }

  private TurnView.GuardRow guardRow(TraceEvent event) {
    return new TurnView.GuardRow(
        event.seq(), event.name(), event.outcome(), PayloadText.line(tree(event.payloadJson())));
  }

  /**
   * Model rounds, tool calls and errors in the order they happened, with the guard events between
   * two model calls kept in place as checks, and an error when the turn left no trace at all.
   */
  private List<TurnView.Step> steps(TurnRows rows, ModelSpan span, Shown shown) {
    List<TurnView.Step> steps = new ArrayList<>();
    for (TraceEvent event : rows.events()) {
      switch (event.type()) {
        case MODEL_CALL -> steps.add(readable(event, () -> modelRow(event, shown, rows.n())));
        case TOOL_CALL -> steps.add(readable(event, () -> toolRow(event, shown, rows.n())));
        case ERROR -> steps.add(readable(event, () -> errorRow(event, shown, rows.n())));
        case GUARD -> {
          if (span.between(event.seq())) {
            addCheck(steps, guardRow(event));
          }
        }
        case STATE_DIFF -> {}
      }
    }
    if (rows.events().isEmpty()) {
      steps.add(new TurnView.ErrorRow(null, "NO_TRACE", "no trace events were stored", null));
    }
    return steps;
  }

  /**
   * The event's row, or an error row when its payload is not the record this build writes, as for a
   * row written before it: the page shows the gap instead of failing.
   */
  private static TurnView.Step readable(TraceEvent event, Supplier<TurnView.Step> row) {
    try {
      return row.get();
    } catch (JacksonException e) {
      return new TurnView.ErrorRow(
          event.seq(),
          "UNREADABLE",
          event.type() + " " + event.name() + " was written in a shape this build does not read",
          null);
    }
  }

  /** A guard event joins the checks just before it, so a run of them reads as one step. */
  private static void addCheck(List<TurnView.Step> steps, TurnView.GuardRow row) {
    if (!steps.isEmpty() && steps.getLast() instanceof TurnView.Checks checks) {
      steps.set(steps.size() - 1, checks.plus(row));
    } else {
      steps.add(new TurnView.Checks(List.of(row)));
    }
  }

  private TurnView.ModelRow modelRow(TraceEvent event, Shown shown, int turnNo) {
    ModelRoundPayload round = payload(event, ModelRoundPayload.class);
    return new TurnView.ModelRow(
        event.seq(),
        round.round(),
        round.call(),
        round.model(),
        round.effort(),
        round.tokens(),
        round.costUsd(),
        round.latencyMs(),
        round.status(),
        round.failure(),
        shown.text(round.summary(), turnNo));
  }

  /** The arguments are the model's words and may repeat what the lead wrote, so they are masked. */
  private TurnView.ToolRow toolRow(TraceEvent event, Shown shown, int turnNo) {
    ToolCallPayload call = payload(event, ToolCallPayload.class);
    return new TurnView.ToolRow(
        event.seq(),
        event.name(),
        event.outcome() == TraceOutcome.PASS,
        shown.text(PayloadText.arguments(call.args()), turnNo),
        SchedulingTools.summary(call.result()),
        SchedulingTools.hint(call.result()),
        call.filtering(),
        SchedulingTools.slots(call.result()),
        call.latencyMs());
  }

  /** The detail may quote the model, a refusal for one, so it is masked like its text. */
  private TurnView.ErrorRow errorRow(TraceEvent event, Shown shown, int turnNo) {
    ErrorPayload error = payload(event, ErrorPayload.class);
    return new TurnView.ErrorRow(
        event.seq(), error.code(), shown.text(error.detail(), turnNo), error.maxAttempts());
  }

  /** Empty when the stored decision no longer reads as this build's {@link Decision}. */
  private Optional<Decision> readable(JsonNode stored) {
    try {
      return Optional.of(json.treeToValue(stored, Decision.class));
    } catch (JacksonException e) {
      return Optional.empty();
    }
  }

  private static TurnView.DecisionView decisionView(Decision decision, TurnRows rows, Shown shown) {
    return new TurnView.DecisionView(
        decision.leadIntent(),
        decision.stage(),
        decision.objectionType(),
        decision.flags(),
        extractedLine(decision.extracted(), shown, rows.n()),
        shown.text(decision.summary(), rows.n()),
        decision.sendReply(),
        decision.goalMet(),
        decision.unsubscribed(),
        decision.conversationEnded(),
        decision.endReason(),
        decision.needsHuman(),
        decision.handoffReason(),
        shown.text(decision.reply(), rows.n()),
        TurnView.ReplyFate.of(
            rows.turn().status(), rows.reply() == null ? null : rows.reply().source()));
  }

  /** "goal save, cover hospital, Bupa silver, 2026-07-27 morning", as the prototype reads it. */
  private static String extractedLine(Decision.Extracted extracted, Shown shown, int turnNo) {
    StringJoiner line = new StringJoiner(", ");
    line.add("goal " + lower(extracted.coverageGoal()));
    line.add("cover " + lower(extracted.coverType()));
    if (extracted.currentCoverNote() != null) {
      line.add(extracted.currentCoverNote());
    }
    if (extracted.preferredDay() != null) {
      line.add(extracted.preferredDay() + " " + lower(extracted.preferredTimeOfDay()));
    }
    return shown.text(line.toString(), turnNo);
  }

  /**
   * The STATE_DIFF event's fields, each with the value the model asked for when an OVERRIDE guard
   * event on that field says code kept another; an overridden field the turn did not change still
   * gets its row.
   */
  private List<TurnView.DiffRow> diff(List<TraceEvent> events) {
    Map<String, TurnView.DiffRow> rows = new LinkedHashMap<>();
    events.stream()
        .filter(event -> event.type() == TraceEventType.STATE_DIFF)
        .forEach(event -> changesOnto(rows, json.readValue(event.payloadJson(), STATE_DIFF)));
    events.stream()
        .filter(event -> event.outcome() == TraceOutcome.OVERRIDE)
        .filter(event -> tree(event.payloadJson()).has("model"))
        .forEach(event -> overrideOnto(rows, event.name(), payload(event, OverridePayload.class)));
    return List.copyOf(rows.values());
  }

  private void changesOnto(Map<String, TurnView.DiffRow> rows, Map<String, StateChange> changes) {
    changes.forEach(
        (field, change) ->
            rows.put(
                field,
                new TurnView.DiffRow(field, words(change.from()), words(change.to()), null)));
  }

  private static void overrideOnto(
      Map<String, TurnView.DiffRow> rows, String field, OverridePayload override) {
    TurnView.DiffRow changed = rows.get(field);
    rows.put(
        field,
        new TurnView.DiffRow(
            field,
            changed == null ? override.code() : changed.before(),
            changed == null ? override.code() : changed.after(),
            override.model()));
  }

  private String words(Object value) {
    return value == null ? PayloadText.NONE : PayloadText.value(json.valueToTree(value));
  }

  private static String lower(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  private <T> T payload(TraceEvent event, Class<T> type) {
    return json.readValue(event.payloadJson(), type);
  }

  private JsonNode tree(String stored) {
    return stored == null ? NullNode.getInstance() : json.readTree(stored);
  }

  private String pretty(JsonNode node) {
    return json.writerWithDefaultPrettyPrinter().writeValueAsString(node);
  }
}
