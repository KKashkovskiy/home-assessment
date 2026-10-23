package com.enrola.anna.lead;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Australian mobile and landline numbers to E.164. Anything that is not an AU number is rejected.
 */
public final class Phones {

  private static final String COUNTRY_CODE = "+61";
  private static final Pattern NOT_DIGIT_OR_PLUS = Pattern.compile("[^0-9+]");

  /**
   * A +61, 61 or 0 prefix, then nine digits led by a mobile (4) or geographic (2, 3, 7, 8) area
   * digit.
   */
  private static final Pattern AUSTRALIAN = Pattern.compile("(?:\\+61|61|0)([2-478]\\d{8})");

  private Phones() {}

  /** "+61412345678", "0412 345 678", "61412345678", "(03) 9123 4567" all become "+61...". */
  public static String toE164(String raw) {
    Matcher number = AUSTRALIAN.matcher(NOT_DIGIT_OR_PLUS.matcher(raw).replaceAll(""));
    if (!number.matches()) {
      throw new IllegalArgumentException("not an Australian number");
    }
    return COUNTRY_CODE + number.group(1);
  }

  /**
   * The nine digits after +61 of a number in the shape {@link #toE164} returns: what follows the
   * leading 0 when an Australian writes it.
   */
  public static String nationalDigits(String e164) {
    return e164.substring(COUNTRY_CODE.length());
  }
}
