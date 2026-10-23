package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The constructs the harness writes, and text that must never become markup. */
class MarkdownLiteTest {

  static final Function<String, String> LINKS = target -> target.equals("two.md") ? "/x/two" : null;

  @Test
  void rendersHeadingsTablesListsParagraphsAndInlineMarks() {
    String markdown =
        """
        # Run
        | A | B |
        |---|---|
        | `x_y` | **y** \\| z |

        - one
        - [two](two.md)

        line one
        line _two_ and snake_case
        """;

    assertThat(MarkdownLite.render(markdown, LINKS))
        .isEqualTo(
            """
            <h3>Run</h3>
            <div class="table-wrap"><table><thead><tr><th>A</th><th>B</th></tr></thead>\
            <tbody><tr><td><code>x_y</code></td><td><strong>y</strong> | z</td></tr></tbody>\
            </table></div>
            <ul><li>one</li><li><a href="/x/two">two</a></li></ul>
            <p>line one<br>line <em>two</em> and snake_case</p>
            """);
  }

  @Test
  void escapesEveryCharacterBeforeAddingTags() {
    String html =
        MarkdownLite.render(
            "<script>alert(1)</script>\n\n| `<b>` | <img src=x> |\n|---|---|\n", LINKS);

    assertThat(html)
        .contains("&lt;script&gt;alert(1)&lt;/script&gt;", "<code>&lt;b&gt;</code>", "&lt;img")
        .doesNotContain("<script", "<img", "<b>");
  }

  /** A result in a table is marked as one, and a failed row stands out. */
  @Test
  void resultsAreMarkedAndFailedRowsToo() {
    String html =
        MarkdownLite.render("| Check | Result |\n|---|---|\n| a | pass |\n| b | FAIL |", LINKS);

    assertThat(html)
        .contains(
            "<tr><td>a</td><td class=\"result pass\">pass</td></tr>",
            "<tr class=\"failed\"><td>b</td><td class=\"result fail\">FAIL</td></tr>");
  }

  /** A lead can send a lone pipe; it is a line of text, not a table row without cells. */
  @Test
  void aRowOfOnePipeIsText() {
    assertThat(MarkdownLite.render("- **Lead:** hi\n|\n", LINKS))
        .isEqualTo("<ul><li><strong>Lead:</strong> hi</li></ul>\n<p>|</p>\n");
    assertThat(MarkdownLite.render("|  ", LINKS)).isEqualTo("<p>|</p>\n");
    assertThat(MarkdownLite.render("||", LINKS)).contains("<table><thead><tr><th></th></tr>");
  }

  @Test
  void aLinkToNoPageOfTheConsoleKeepsOnlyItsText() {
    assertThat(MarkdownLite.render("[evil](javascript:alert) and [x](other.md)", LINKS))
        .isEqualTo("<p>evil and x</p>\n");
  }
}
