package com.enrola.anna.guard;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whole-message opt-out keywords. It only matches; the caller decides what a match means. Prose
 * with "stop" in it is the model's to judge, and so are full-width look-alikes ("ＳＴＯＰ"), which are
 * not normalised.
 */
public final class StopKeywords {

  private static final Set<String> KEYWORDS =
      Set.of("STOP", "STOPALL", "UNSUBSCRIBE", "OPTOUT", "CANCEL", "END", "QUIT");

  /** Unicode punctuation and whitespace: curly quotes, ellipses, dashes, non-breaking spaces. */
  private static final Pattern PUNCTUATION_AND_SPACE = Pattern.compile("(?U)[\\p{Punct}\\s]+");

  private StopKeywords() {}

  /**
   * True when the message, upper-cased with punctuation and spaces removed, is a keyword: " Stop. "
   * and "opt-out" match, "stop texting me" and "Cancel that, Thursday's better" do not.
   */
  public static boolean isStop(String text) {
    String key = PUNCTUATION_AND_SPACE.matcher(text.toUpperCase(Locale.ROOT)).replaceAll("");
    return KEYWORDS.contains(key);
  }
}
