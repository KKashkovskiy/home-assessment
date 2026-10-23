package com.enrola.anna.guard;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the clause around a position in a reply starts and ends: at a comma, a semicolon, " but "
 * or the end of a sentence. A negation or a decline only speaks for its own clause.
 */
final class Clauses {

  private static final Pattern BREAK =
      Pattern.compile(",|;| but |[.?!](?=\\s|$)", Pattern.CASE_INSENSITIVE);

  private Clauses() {}

  /** Just after the last break before {@code at}, or 0. */
  static int start(String text, int at) {
    return BREAK.matcher(text).region(0, at).results().mapToInt(MatchResult::end).max().orElse(0);
  }

  /** The first break at or after {@code at}, or the end of the text. */
  static int end(String text, int at) {
    Matcher found = BREAK.matcher(text);
    return found.find(at) ? found.start() : text.length();
  }
}
