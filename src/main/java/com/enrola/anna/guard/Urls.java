package com.enrola.anna.guard;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The URL allow-list: comparato.au and www.comparato.au. A bare domain counts as a link, and an
 * email address is always off the list, whatever its domain.
 */
public final class Urls {

  private static final List<String> ALLOWED_HOSTS = List.of("comparato.au", "www.comparato.au");
  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

  /** "scheme://host"; a userinfo part ("https://a@b") is an email too, caught first. */
  private static final Pattern SCHEME =
      Pattern.compile("\\b[a-z][a-z0-9+.-]*://([a-z0-9.-]+)", FLAGS);

  /** word.tld with a known TLD, then an optional country suffix and path: "bit.ly/x". */
  private static final Pattern BARE =
      Pattern.compile(
          "\\b((?:[a-z0-9-]+\\.)+(?:com|net|org|au|io|co|nz|uk|info|biz|xyz|ly|me)"
              + "(?:\\.[a-z]{2})?)\\b(?:/\\S*)?",
          FLAGS);

  private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@(?:[\\w-]+\\.)+[a-z]{2,}", FLAGS);

  private Urls() {}

  /** True when the reply names an email address or a host outside the allow-list. */
  public static boolean offList(String reply) {
    return EMAIL.matcher(reply).find() || hasOtherHost(SCHEME, reply) || hasOtherHost(BARE, reply);
  }

  private static boolean hasOtherHost(Pattern link, String reply) {
    return link.matcher(reply)
        .results()
        .anyMatch(found -> !ALLOWED_HOSTS.contains(found.group(1).toLowerCase(Locale.ROOT)));
  }
}
