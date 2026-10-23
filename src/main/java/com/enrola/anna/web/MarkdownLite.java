package com.enrola.anna.web;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

/**
 * Renders the Markdown the eval harness writes, which uses five constructs: headings, paragraphs,
 * bullet lists, pipe tables, and inline code, bold, italics and links. Every character of the input
 * is escaped before a tag is added, so text from a transcript never becomes markup; a link renders
 * only when {@code links} maps its target to a page of the console, and its text stays otherwise.
 * Headings sit two levels down, under the page's title and the panel's heading.
 */
final class MarkdownLite {

  private static final Pattern HEADING = Pattern.compile("^(#{1,4}) (.*)$");
  private static final Pattern SEPARATOR = Pattern.compile("^\\|[\\s|:-]+\\|$");
  private static final Pattern CELL_BREAK = Pattern.compile("(?<!\\\\)\\|");
  private static final Pattern CODE = Pattern.compile("`([^`]+)`");
  private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\(([^)\\s]+)\\)");
  private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
  private static final Pattern ITALIC = Pattern.compile("(?<!\\w)_(.+?)_(?!\\w)");

  private MarkdownLite() {}

  /** {@code markdown} as HTML; {@code links} gives a target's href, or null to drop the link. */
  static String render(String markdown, Function<String, String> links) {
    var html = new StringBuilder();
    List<String> block = new ArrayList<>();
    for (String line : markdown.lines().toList()) {
      if (!block.isEmpty() && kind(line) != kind(block.getFirst())) {
        html.append(block(block, links));
        block.clear();
      }
      if (!line.isBlank()) {
        block.add(line.strip());
      }
    }
    if (!block.isEmpty()) {
      html.append(block(block, links));
    }
    return html.toString();
  }

  /** What block a line belongs to; a heading is a block of its own. */
  private enum Kind {
    BLANK,
    HEADING,
    TABLE,
    LIST,
    PARAGRAPH
  }

  private static Kind kind(String line) {
    String text = line.strip();
    if (text.isEmpty()) {
      return Kind.BLANK;
    }
    if (HEADING.matcher(text).matches()) {
      return Kind.HEADING;
    }
    if (text.startsWith("|") && text.length() > 1) {
      return Kind.TABLE;
    }
    return text.startsWith("- ") ? Kind.LIST : Kind.PARAGRAPH;
  }

  private static String block(List<String> lines, Function<String, String> links) {
    return switch (kind(lines.getFirst())) {
      case HEADING -> lines.stream().map(line -> heading(line, links)).reduce("", String::concat);
      case TABLE -> table(lines, links);
      case LIST ->
          "<ul>"
              + lines.stream()
                  .map(line -> "<li>" + inline(line.substring(2), links) + "</li>")
                  .reduce("", String::concat)
              + "</ul>\n";
      case PARAGRAPH ->
          "<p>"
              + String.join("<br>", lines.stream().map(line -> inline(line, links)).toList())
              + "</p>\n";
      case BLANK -> "";
    };
  }

  private static String heading(String line, Function<String, String> links) {
    return HEADING
        .matcher(line)
        .replaceAll(
            heading -> {
              int level = heading.group(1).length() + 2;
              return Matcher.quoteReplacement(
                  "<h%d>%s</h%d>%n".formatted(level, inline(heading.group(2), links), level));
            });
  }

  /**
   * The first row heads the table; a separator row under it is dropped. A body cell that is a
   * result, PASS, FAIL or INVALID, is marked as one, and a row with a FAIL as failed, so a failure
   * stands out of a long table.
   */
  private static String table(List<String> rows, Function<String, String> links) {
    var html = new StringBuilder("<div class=\"table-wrap\"><table><thead><tr>");
    cells(rows.getFirst())
        .forEach(text -> html.append("<th>%s</th>".formatted(inline(text, links))));
    html.append("</tr></thead><tbody>");
    rows.stream()
        .skip(1)
        .filter(row -> !SEPARATOR.matcher(row).matches())
        .forEach(row -> html.append(bodyRow(cells(row), links)));
    return html.append("</tbody></table></div>\n").toString();
  }

  private static String bodyRow(List<String> cells, Function<String, String> links) {
    boolean failed = cells.stream().anyMatch(text -> text.equalsIgnoreCase("FAIL"));
    var html = new StringBuilder(failed ? "<tr class=\"failed\">" : "<tr>");
    for (String text : cells) {
      html.append("<td%s>%s</td>".formatted(resultClass(text), inline(text, links)));
    }
    return html.append("</tr>").toString();
  }

  private static String resultClass(String text) {
    return switch (text.toUpperCase(Locale.ROOT)) {
      case "PASS" -> " class=\"result pass\"";
      case "FAIL" -> " class=\"result fail\"";
      case "INVALID" -> " class=\"result unknown\"";
      default -> "";
    };
  }

  /** A row's cells, stripped, with escaped pipes as pipes. */
  private static List<String> cells(String row) {
    String inner = row.substring(1, row.endsWith("|") ? row.length() - 1 : row.length());
    return Arrays.stream(CELL_BREAK.split(inner, -1))
        .map(text -> text.strip().replace("\\|", "|"))
        .toList();
  }

  /** Code spans keep their text as is; the rest gets links, bold and italics. */
  private static String inline(String text, Function<String, String> links) {
    var html = new StringBuilder();
    Matcher code = CODE.matcher(text);
    int at = 0;
    while (code.find()) {
      html.append(styled(text.substring(at, code.start()), links))
          .append("<code>")
          .append(escape(code.group(1)))
          .append("</code>");
      at = code.end();
    }
    return html.append(styled(text.substring(at), links)).toString();
  }

  private static String styled(String text, Function<String, String> links) {
    String html = LINK.matcher(escape(text)).replaceAll(link -> link(link, links));
    html = BOLD.matcher(html).replaceAll("<strong>$1</strong>");
    return ITALIC.matcher(html).replaceAll("<em>$1</em>");
  }

  private static String link(MatchResult link, Function<String, String> links) {
    String href = links.apply(link.group(2));
    return Matcher.quoteReplacement(
        href == null
            ? link.group(1)
            : "<a href=\"" + escape(href) + "\">" + link.group(1) + "</a>");
  }

  /** The five characters HTML gives meaning to; the rest stays as written, bullets included. */
  private static String escape(String text) {
    return HtmlUtils.htmlEscape(text, StandardCharsets.UTF_8.name());
  }
}
