package com.enrola.anna.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * The scenario editor's posted form, and the one mapping between its fields and a scenario's YAML.
 * Each field is named by the YAML path it edits, {@code expect.lead_intent} say, and an empty field
 * removes its key, so the form writes no value nobody chose. Keys the form has no field for, such
 * as a story's {@code config} or an inline lead, pass through untouched; the YAML view edits those.
 * A form that changed nothing leaves the text as its author wrote it, so switching views alone
 * rewrites nothing; a changed one writes the file afresh.
 *
 * <p>Besides the fields it posts {@code id}, the file the scenario saves to; {@code original}, the
 * id it was opened as, empty for a new one; {@code source}, the view that holds the edits ({@code
 * form} or {@code yaml}); {@code yaml}, that view's text or the text the form was filled from; and
 * the history rows as parallel {@code history.speaker}, {@code history.text} and {@code
 * history.offered_slots} values.
 */
record ScenarioForm(MultiValueMap<String, String> posted) {

  /** How a field's text maps to YAML. */
  enum Type {
    /** The text; empty removes the key. */
    TEXT,
    /** A number; text that is not one stays text, for the schema to name. */
    INTEGER,
    /** {@code true}, {@code false}, or empty for not asserted. */
    BOOLEAN,
    /** Comma-separated; {@code []} is an empty list, which says "none". */
    LIST,
    /** One entry a line, kept as typed: a regex's spaces are part of it. */
    LINES,
    /** A checkbox: ticked is {@code true}, unticked removes the key. */
    CHECK
  }

  /** The scenario kinds a field belongs to; a story's fields are removed from a turn, and back. */
  enum Kinds {
    BOTH,
    TURN,
    STORY;

    boolean include(String kind) {
      return this == BOTH || name().equalsIgnoreCase(kind);
    }
  }

  /** The editor's two views: the fields, or the YAML they stand for. */
  public enum View {
    FORM,
    YAML;

    /** As the page posts it. */
    public String value() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** {@code yaml} is the YAML view; anything else the form. */
    static View of(String value) {
      return "yaml".equals(value) ? YAML : FORM;
    }
  }

  /** A field: the YAML path it edits, how, and the kinds it belongs to. */
  record Field(String path, Type type, Kinds kinds) {}

  /**
   * One seeded line: {@code agent} or {@code lead}, its text, and an agent line's offered slots.
   */
  record HistoryRow(String speaker, String text, String offeredSlots) {}

  static final List<Field> FIELDS =
      List.of(
          new Field("title", Type.TEXT, Kinds.BOTH),
          new Field("tags", Type.LIST, Kinds.BOTH),
          new Field("clock", Type.TEXT, Kinds.BOTH),
          new Field("calendar", Type.TEXT, Kinds.BOTH),
          new Field("judge", Type.LINES, Kinds.BOTH),
          new Field("seed.objections_used", Type.INTEGER, Kinds.TURN),
          new Field("seed.off_topic_streak", Type.INTEGER, Kinds.TURN),
          new Field("seed.booking", Type.TEXT, Kinds.TURN),
          new Field("seed.ended", Type.TEXT, Kinds.TURN),
          new Field("message", Type.TEXT, Kinds.TURN),
          new Field("persona", Type.TEXT, Kinds.STORY),
          new Field("stop_when", Type.LINES, Kinds.STORY),
          new Field("expect.lead_intent", Type.TEXT, Kinds.BOTH),
          new Field("expect.stage", Type.TEXT, Kinds.BOTH),
          new Field("expect.end_reason", Type.TEXT, Kinds.BOTH),
          new Field("expect.handoff_reason", Type.TEXT, Kinds.BOTH),
          new Field("expect.flags", Type.LIST, Kinds.BOTH),
          new Field("expect.tool_called", Type.LIST, Kinds.BOTH),
          new Field("expect.goal_met", Type.BOOLEAN, Kinds.BOTH),
          new Field("expect.unsubscribed", Type.BOOLEAN, Kinds.BOTH),
          new Field("expect.conversation_ended", Type.BOOLEAN, Kinds.BOTH),
          new Field("expect.needs_human", Type.BOOLEAN, Kinds.BOTH),
          new Field("expect.model_called", Type.BOOLEAN, Kinds.BOTH),
          new Field("expect.reply_sent", Type.BOOLEAN, Kinds.BOTH),
          new Field("expect.ends_with_question", Type.BOOLEAN, Kinds.BOTH),
          new Field("expect.reply_matches", Type.LINES, Kinds.BOTH),
          new Field("expect.reply_not_matches", Type.LINES, Kinds.BOTH),
          new Field("expect.booking_count", Type.INTEGER, Kinds.STORY),
          new Field("expect.retries_max", Type.INTEGER, Kinds.STORY),
          new Field("expect.replies_after_goal_max", Type.INTEGER, Kinds.STORY),
          new Field("expect.replies_after_end_max", Type.INTEGER, Kinds.STORY),
          new Field("expect.transcript_matches", Type.LINES, Kinds.STORY),
          new Field("expect.every_reply.max_chars", Type.INTEGER, Kinds.STORY),
          new Field("expect.every_reply.reply_not_matches", Type.LINES, Kinds.STORY),
          new Field("expect.every_reply.one_question_max", Type.CHECK, Kinds.STORY),
          new Field("expect.every_reply.ends_with_question_until_goal", Type.CHECK, Kinds.STORY));

  /** The seeded leads the form offers; any other lead is an inline record, kept as written. */
  static final List<String> LEADS = List.of("lauren", "john", "jane");

  static final String INLINE_LEAD = "inline";

  /** Block style, quotes only where YAML needs them, lines never folded. */
  private static final YAMLMapper YAML =
      YAMLMapper.builder()
          .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
          .disable(YAMLWriteFeature.SPLIT_LINES)
          .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
          .enable(YAMLWriteFeature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
          .enable(YAMLWriteFeature.LITERAL_BLOCK_STYLE)
          .enable(YAMLWriteFeature.INDENT_ARRAYS_WITH_INDICATOR)
          .build();

  String id() {
    return value("id");
  }

  String original() {
    return value("original");
  }

  /** The view holding the edits; the form unless the YAML view says so. */
  View source() {
    return View.of(value("source"));
  }

  String yaml() {
    return value("yaml");
  }

  /** The Duplicate button sent the form. */
  boolean duplicating() {
    return !value("duplicate").isEmpty();
  }

  /**
   * The scenario's text as edited: the YAML view's as typed, or the form's fields laid over the
   * text it was filled from and written afresh, comments dropped; a form that changed nothing keeps
   * the text as it was. Empty when the text the fields go over is not a YAML mapping.
   */
  Optional<String> text() {
    if (source() == View.YAML) {
      return Optional.of(yaml());
    }
    return read(yaml())
        .map(
            base -> {
              ObjectNode edited = applied(base);
              return edited.equals(base) ? yaml() : YAML.writeValueAsString(edited);
            });
  }

  /** {@code yaml} as a tree; empty when it does not parse or is not a mapping. */
  static Optional<ObjectNode> read(String yaml) {
    try {
      return YAML.readTree(yaml) instanceof ObjectNode tree ? Optional.of(tree) : Optional.empty();
    } catch (JacksonException e) {
      return Optional.empty();
    }
  }

  /** The text's tree with the fields laid over it, the history rows included. */
  private ObjectNode applied(ObjectNode base) {
    ObjectNode tree = base.deepCopy();
    tree.put("id", id());
    String kind = value("kind").equals("story") ? "story" : "turn";
    tree.put("kind", kind);
    String lead = value("lead");
    if (LEADS.contains(lead) || !tree.has("lead")) {
      tree.put("lead", lead);
    }
    for (Field field : FIELDS) {
      JsonNode next = field.kinds().include(kind) ? node(field.type(), value(field.path())) : null;
      if (!unchanged(field.type(), at(tree, field.path()), next)) {
        set(tree, field.path(), next);
      }
    }
    set(tree, "history", kind.equals("turn") ? historyNode(postedHistory()) : null);
    return tree;
  }

  /** The fields' text as typed, by path, with the id, kind and lead. */
  Map<String, String> typed() {
    Map<String, String> typed = new LinkedHashMap<>();
    for (String name : List.of("id", "kind", "lead")) {
      typed.put(name, value(name));
    }
    FIELDS.forEach(field -> typed.put(field.path(), value(field.path())));
    return typed;
  }

  /** The history rows as posted, with a line added or removed when a row's button asked. */
  List<HistoryRow> postedHistory() {
    List<String> speakers = all("history.speaker");
    List<String> texts = all("history.text");
    List<String> slots = all("history.offered_slots");
    List<HistoryRow> rows = new ArrayList<>();
    for (int index = 0; index < speakers.size(); index++) {
      rows.add(
          new HistoryRow(
              speakers.get(index),
              index < texts.size() ? texts.get(index) : "",
              index < slots.size() ? slots.get(index) : ""));
    }
    String removed = value("history.remove");
    if (removed.matches("\\d{1,3}") && Integer.parseInt(removed) < rows.size()) {
      rows.remove(Integer.parseInt(removed));
    }
    if (value("history.add").equals("line")) {
      boolean leadsNext = !rows.isEmpty() && rows.getLast().speaker().equals("agent");
      rows.add(new HistoryRow(leadsNext ? "lead" : "agent", "", ""));
    }
    return rows;
  }

  /** Every field's text as the form shows it, by path; an absent key is empty. */
  static Map<String, String> fieldTexts(JsonNode tree) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("id", tree.path("id").asString(""));
    values.put("kind", tree.path("kind").asString("turn"));
    values.put(
        "lead", tree.path("lead").isString() ? tree.path("lead").stringValue() : INLINE_LEAD);
    for (Field field : FIELDS) {
      values.put(field.path(), text(field.type(), at(tree, field.path())));
    }
    return values;
  }

  /** The tree's history as rows. */
  static List<HistoryRow> history(JsonNode tree) {
    return StreamSupport.stream(tree.path("history").spliterator(), false)
        .map(
            line ->
                line.has("lead")
                    ? new HistoryRow("lead", line.path("lead").asString(""), "")
                    : new HistoryRow(
                        "agent",
                        line.path("agent").asString(""),
                        text(Type.LIST, line.path("offered_slots"))))
        .toList();
  }

  private static ArrayNode historyNode(List<HistoryRow> rows) {
    ArrayNode history = JsonNodeFactory.instance.arrayNode();
    for (HistoryRow row : rows) {
      if (row.text().isBlank() && row.offeredSlots().isBlank()) {
        continue;
      }
      ObjectNode line = history.addObject();
      line.put(row.speaker().equals("lead") ? "lead" : "agent", row.text().strip());
      JsonNode slots = node(Type.LIST, row.offeredSlots());
      if (row.speaker().equals("agent") && slots != null) {
        line.set("offered_slots", slots);
      }
    }
    return history.isEmpty() ? null : history;
  }

  /** The field's text as YAML; null for an empty field, which removes the key. */
  private static JsonNode node(Type type, String text) {
    JsonNodeFactory nodes = JsonNodeFactory.instance;
    String value = text.strip();
    return switch (type) {
      case TEXT -> value.isEmpty() ? null : nodes.stringNode(value);
      case INTEGER -> value.isEmpty() ? null : integer(value);
      case BOOLEAN -> value.isEmpty() ? null : nodes.booleanNode(value.equals("true"));
      case CHECK -> value.equals("true") ? nodes.booleanNode(true) : null;
      case LIST ->
          value.equals("[]")
              ? nodes.arrayNode()
              : list(Arrays.stream(value.split(",", -1)).map(String::strip));
      case LINES -> list(Arrays.stream(text.split("\\R", -1)));
    };
  }

  private static JsonNode integer(String value) {
    try {
      return JsonNodeFactory.instance.numberNode(Integer.parseInt(value));
    } catch (NumberFormatException _) {
      return JsonNodeFactory.instance.stringNode(value);
    }
  }

  /** The items that are not blank, as typed; null when none is. */
  private static JsonNode list(Stream<String> items) {
    List<String> kept = items.filter(item -> !item.isBlank()).toList();
    if (kept.isEmpty()) {
      return null;
    }
    ArrayNode list = JsonNodeFactory.instance.arrayNode();
    kept.forEach(list::add);
    return list;
  }

  /** The YAML at {@code path} as a field's text. */
  private static String text(Type type, JsonNode node) {
    if (node.isMissingNode() || node.isNull()) {
      return "";
    }
    return switch (type) {
      case LIST ->
          node.isArray() && node.isEmpty() ? "[]" : items(node).collect(Collectors.joining(", "));
      case LINES -> items(node).collect(Collectors.joining("\n"));
      case TEXT, INTEGER, BOOLEAN, CHECK -> node.asString();
    };
  }

  private static Stream<String> items(JsonNode node) {
    return node.isArray()
        ? StreamSupport.stream(node.spliterator(), false).map(JsonNode::asString)
        : Stream.of(node.asString());
  }

  /**
   * True when {@code next} says what is there already: the same text but for the ends a field
   * trims, such as the newline a YAML block keeps, or an unticked box over a {@code false}, which
   * the box cannot show apart from no key.
   */
  private static boolean unchanged(Type type, JsonNode current, JsonNode next) {
    if (type == Type.CHECK && next == null) {
      return current.isBoolean() && !current.booleanValue();
    }
    return current.isString()
        && next != null
        && next.isString()
        && current.stringValue().strip().equals(next.stringValue());
  }

  private static JsonNode at(JsonNode tree, String path) {
    JsonNode node = tree;
    for (String key : path.split("\\.", -1)) {
      node = node.path(key);
    }
    return node;
  }

  /**
   * Sets {@code value} at {@code path}, making the mappings on the way; null removes the key, and a
   * mapping left empty goes with it.
   */
  private static void set(ObjectNode tree, String path, JsonNode value) {
    String[] keys = path.split("\\.", 2);
    if (keys.length == 1) {
      if (value == null) {
        tree.remove(keys[0]);
      } else {
        tree.set(keys[0], value);
      }
      return;
    }
    ObjectNode child;
    if (tree.get(keys[0]) instanceof ObjectNode existing) {
      child = existing;
    } else if (value == null) {
      return;
    } else {
      child = tree.putObject(keys[0]);
    }
    set(child, keys[1], value);
    if (child.isEmpty()) {
      tree.remove(keys[0]);
    }
  }

  /** The field's text, its line breaks as YAML writes them whatever the browser sent. */
  private String value(String name) {
    return Objects.requireNonNullElse(posted.getFirst(name), "").replace("\r\n", "\n");
  }

  private List<String> all(String name) {
    return Objects.requireNonNullElse(posted.get(name), List.of());
  }
}
