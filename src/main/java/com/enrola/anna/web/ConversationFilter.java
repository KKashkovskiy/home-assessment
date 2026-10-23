package com.enrola.anna.web;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.ObjectionType;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.ConversationStatus;
import com.enrola.anna.conversation.Source;
import com.enrola.anna.lead.Suppression;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Query-string filter for the conversation list; every field optional, AND-combined. Bound from the
 * GET parameters by name and written back into links, so the list keeps its state across clicks. A
 * blank search is no search. The fields after {@code q} have no control in the list's form: they
 * let an analytics figure link to exactly the conversations it counted, so they show only as chips
 * and ride along as hidden fields. {@code reached} is the highest stage passed, {@code since} the
 * earliest start; the rest are described on {@link Param}.
 */
public record ConversationFilter(
    ConversationStatus status,
    EndReason endReason,
    Stage stage,
    Flag flag,
    Boolean needsHuman,
    Source source,
    String q,
    Boolean replied,
    Suppression.Reason optOutReason,
    HandoffReason handoffReason,
    ObjectionType objectionType,
    Stage reached,
    String promptVersion,
    String model,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
    Boolean guardCaught,
    Boolean retried,
    Boolean errored,
    Boolean reset) {

  public static final ConversationFilter NONE =
      new ConversationFilter(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
          null, null, null, null);

  /**
   * The list's parameters in URL order: each one's name, its chip's label (none for the search,
   * which stays in its box), whether the list's form has a control for it, and its value in a
   * filter. {@code guardCaught}: a guard caught the model on some turn; {@code retried}: a reply
   * was repaired or replaced; {@code errored}: a turn failed; {@code reset}: the console started
   * over.
   */
  public enum Param {
    STATUS("status", "state", true),
    END_REASON("endReason", "end reason", true),
    STAGE("stage", "stage", true),
    FLAG("flag", "flag", true),
    NEEDS_HUMAN("needsHuman", "needs human", true),
    SOURCE("source", "source", true),
    Q("q", null, true),
    REPLIED("replied", "replied", false),
    OPT_OUT_REASON("optOutReason", "opt-out", false),
    HANDOFF_REASON("handoffReason", "handoff", false),
    OBJECTION_TYPE("objectionType", "objection", false),
    REACHED("reached", "reached", false),
    PROMPT_VERSION("promptVersion", "prompt", false),
    MODEL("model", "model", false),
    SINCE("since", "started from", false),
    GUARD_CAUGHT("guardCaught", "guard caught the model", false),
    RETRIED("retried", "reply repaired or replaced", false),
    ERRORED("errored", "a turn failed", false),
    RESET("reset", "reset", false);

    private final String parameter;
    private final String label;
    private final boolean formControl;

    Param(String parameter, String label, boolean formControl) {
      this.parameter = parameter;
      this.label = label;
      this.formControl = formControl;
    }

    /** What the chip calls this parameter. */
    public String label() {
      return label;
    }
  }

  /**
   * One removable chip in the list header: what it filters on, its value, and the list without it.
   */
  public record Chip(Param param, Object value, String removeUrl) {}

  public ConversationFilter {
    q = blankToNull(q);
    promptVersion = blankToNull(promptVersion);
    model = blankToNull(model);
  }

  /**
   * The conversations an analytics filter counts: started from {@code since} on the source, model
   * and prompt version given, null meaning any, and never a RESET one.
   */
  public static ConversationFilter cohort(
      Source source, String promptVersion, String model, Instant since) {
    return new ConversationFilter(
        null,
        null,
        null,
        null,
        null,
        source,
        null,
        null,
        null,
        null,
        null,
        null,
        promptVersion,
        model,
        since,
        null,
        null,
        null,
        false);
  }

  public boolean isEmpty() {
    return set().isEmpty();
  }

  /** One chip per set field that has a label. */
  public List<Chip> chips() {
    Map<Param, String> set = set();
    return set.keySet().stream()
        .filter(param -> param.label != null)
        .map(
            param ->
                new Chip(param, value(param), ConversationsController.LIST + without(set, param)))
        .toList();
  }

  /**
   * The set fields the list's form has no control for, as name and value, so applying the form
   * keeps the cohort an analytics figure linked to.
   */
  public List<Map.Entry<String, String>> hiddenParams() {
    return set().entrySet().stream()
        .filter(entry -> !entry.getKey().formControl)
        .map(entry -> Map.entry(entry.getKey().parameter, entry.getValue()))
        .toList();
  }

  /** The stages a conversation at or past {@code reached} may be at; every stage when unset. */
  public List<Stage> stagesReached() {
    return List.copyOf(EnumSet.range(reached == null ? Stage.ENGAGE : reached, Stage.BOOKED));
  }

  /** This filter as a query string, {@code ?a=b&c=d}, or empty when nothing is set. */
  public String toQuery() {
    return query(set());
  }

  /** {@code path} with this filter as its query string. */
  public String url(String path) {
    return path + toQuery();
  }

  /** The list with this filter and the start panel open, the start button's link without script. */
  public String startUrl() {
    return ConversationsController.LIST + toQuery() + (isEmpty() ? "?" : "&") + "start=true";
  }

  /** {@code path} with this filter narrowed by one more field. */
  public String url(String path, Param param, Object value) {
    return url(path, Map.of(param, value));
  }

  /** {@code path} with this filter narrowed by more fields, which replace any already set. */
  public String url(String path, Map<Param, ?> narrowing) {
    Map<Param, String> set = set();
    narrowing.forEach((param, value) -> set.put(param, value.toString()));
    return path + query(set);
  }

  /** The set fields in {@link Param} order, as text. */
  private Map<Param, String> set() {
    Map<Param, String> set = new EnumMap<>(Param.class);
    for (Param param : Param.values()) {
      Object value = value(param);
      if (value != null) {
        set.put(param, value.toString());
      }
    }
    return set;
  }

  /** This filter's value for {@code param}, null when unset. */
  private Object value(Param param) {
    return switch (param) {
      case STATUS -> status;
      case END_REASON -> endReason;
      case STAGE -> stage;
      case FLAG -> flag;
      case NEEDS_HUMAN -> needsHuman;
      case SOURCE -> source;
      case Q -> q;
      case REPLIED -> replied;
      case OPT_OUT_REASON -> optOutReason;
      case HANDOFF_REASON -> handoffReason;
      case OBJECTION_TYPE -> objectionType;
      case REACHED -> reached;
      case PROMPT_VERSION -> promptVersion;
      case MODEL -> model;
      case SINCE -> since;
      case GUARD_CAUGHT -> guardCaught;
      case RETRIED -> retried;
      case ERRORED -> errored;
      case RESET -> reset;
    };
  }

  private static String without(Map<Param, String> set, Param param) {
    Map<Param, String> rest = new EnumMap<>(set);
    rest.remove(param);
    return query(rest);
  }

  private static String query(Map<Param, String> set) {
    return set.entrySet().stream()
        .map(
            entry ->
                entry.getKey().parameter
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&", set.isEmpty() ? "" : "?", ""));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
