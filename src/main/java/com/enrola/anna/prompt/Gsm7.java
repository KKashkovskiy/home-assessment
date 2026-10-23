package com.enrola.anna.prompt;

import static java.util.Map.entry;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The GSM-7 character set every SMS is written in, so a 160-character segment holds 160 characters.
 * Templates must already be clean; a model reply is normalised to it before any check measures it,
 * so the lead's phone shows exactly what was measured.
 */
public final class Gsm7 {

  /**
   * What models write instead of a GSM-7 character, by code point. Other dashes and spaces are
   * caught by their Unicode category, so words never run together.
   */
  private static final Map<Integer, String> SUBSTITUTES =
      Map.ofEntries(
          entry(0x2018, "'"), // left single quote
          entry(0x2019, "'"), // right single quote, the usual apostrophe
          entry(0x201A, "'"), // low single quote
          entry(0x201B, "'"), // reversed single quote
          entry(0x201C, "\""), // left double quote
          entry(0x201D, "\""), // right double quote
          entry(0x201E, "\""), // low double quote
          entry(0x2212, "-"), // minus sign
          entry(0x2026, "..."), // ellipsis
          entry(0x0009, " "), // tab
          entry(0x00B7, "-"), // middle dot
          entry(0x2022, "-")); // bullet

  private static final String BASIC =
      "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
          + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
  private static final String EXTENSION = "^{}\\[~]|€";
  private static final Pattern SPACE_RUN = Pattern.compile(" {2,}");

  private Gsm7() {}

  /**
   * {@code text} as a handset receives it: substitutes mapped, any other dash a hyphen and any
   * other space a space, anything else outside the set (emoji, full-width forms, other scripts)
   * dropped, runs of spaces collapsed, ends stripped.
   */
  public static String normalise(String text) {
    StringBuilder normalised = new StringBuilder(text.length());
    text.codePoints().forEach(codePoint -> normalised.append(toGsm7(codePoint)));
    return SPACE_RUN.matcher(normalised).replaceAll(" ").strip();
  }

  /** True when every character is in the basic set or its extension; spacing is not judged. */
  public static boolean isClean(String text) {
    return text.codePoints().allMatch(Gsm7::isGsm7);
  }

  private static String toGsm7(int codePoint) {
    if (isGsm7(codePoint)) {
      return Character.toString(codePoint);
    }
    if (SUBSTITUTES.containsKey(codePoint)) {
      return SUBSTITUTES.get(codePoint);
    }
    if (Character.getType(codePoint) == Character.DASH_PUNCTUATION) {
      return "-";
    }
    return Character.isSpaceChar(codePoint) ? " " : "";
  }

  private static boolean isGsm7(int codePoint) {
    return BASIC.indexOf(codePoint) >= 0 || EXTENSION.indexOf(codePoint) >= 0;
  }
}
