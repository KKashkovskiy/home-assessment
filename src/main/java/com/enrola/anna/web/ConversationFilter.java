package com.enrola.anna.web;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.ConversationStatus;
import com.enrola.anna.conversation.Source;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Query-string filter for the conversation list; every field optional, AND-combined. Bound from the
 * GET parameters by name and written back into links, so the list keeps its state across clicks.
 * Analytics drill-down links reuse it. A blank search is no search.
 */
public record ConversationFilter(
    ConversationStatus status,
    EndReason endReason,
    Stage stage,
    Flag flag,
    Boolean needsHuman,
    Source source,
    String q) {

  public static final ConversationFilter NONE =
      new ConversationFilter(null, null, null, null, null, null, null);

  private static final Map<String, String> LABELS =
      Map.of(
          "status", "state",
          "endReason", "end reason",
          "stage", "stage",
          "flag", "flag",
          "needsHuman", "needs human",
          "source", "source");

  /** One removable chip in the list header: what it filters on, and the list without it. */
  public record Chip(String label, String value, String removeUrl) {}

  public ConversationFilter {
    q = q == null || q.isBlank() ? null : q.strip();
  }

  public boolean isEmpty() {
    return params().isEmpty();
  }

  /** One chip per set field; the search text stays in its box rather than becoming a chip. */
  public List<Chip> chips() {
    Map<String, String> params = params();
    return params.keySet().stream()
        .filter(LABELS::containsKey)
        .map(
            key ->
                new Chip(
                    LABELS.get(key),
                    shown(params.get(key)),
                    ConversationsController.LIST + without(params, key)))
        .toList();
  }

  /** This filter as a query string, {@code ?a=b&c=d}, or empty when nothing is set. */
  public String toQuery() {
    return query(params());
  }

  /** {@code path} with this filter as its query string. */
  public String url(String path) {
    return path + toQuery();
  }

  /** {@link #url(String)} with one more parameter, such as the flag that opens the start panel. */
  public String url(String path, String key, String value) {
    Map<String, String> params = params();
    params.put(key, value);
    return path + query(params);
  }

  /** The set fields in declaration order, by their parameter names. */
  private Map<String, String> params() {
    Map<String, String> params = new LinkedHashMap<>();
    put(params, "status", status);
    put(params, "endReason", endReason);
    put(params, "stage", stage);
    put(params, "flag", flag);
    put(params, "needsHuman", needsHuman);
    put(params, "source", source);
    put(params, "q", q);
    return params;
  }

  private static void put(Map<String, String> params, String key, Object value) {
    if (value != null) {
      params.put(key, value.toString());
    }
  }

  private static String without(Map<String, String> params, String key) {
    Map<String, String> rest = new LinkedHashMap<>(params);
    rest.remove(key);
    return query(rest);
  }

  private static String query(Map<String, String> params) {
    return params.entrySet().stream()
        .map(p -> p.getKey() + "=" + URLEncoder.encode(p.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&", params.isEmpty() ? "" : "?", ""));
  }

  /** {@code needsHuman=true} reads as yes, the way every badge says yes or no. */
  private static String shown(String value) {
    return switch (value) {
      case "true" -> "yes";
      case "false" -> "no";
      default -> value.replace('_', ' ');
    };
  }
}
