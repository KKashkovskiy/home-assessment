package com.enrola.anna.guard;

import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds the clock times in a reply and checks each against the offered slot labels and the booking
 * label. A bare number counts as a time only after "at" or when a later time in the same list
 * carries a meridiem ("9 or 9:30am"). False negatives are acceptable; a false positive costs a
 * repair call.
 */
public final class TimeMentions {

  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

  /**
   * noon, 9am, 9.30am, 9:30, 09:30, 12:00 PM and bare hours. The lookarounds keep out digits inside
   * a word, an amount, a decimal, a thousands group, a date or a phone number ($350, 1.5, 1,000,
   * 27/7, +61); a dash may precede, so "1-3pm" is two times.
   */
  private static final Pattern TIME =
      Pattern.compile(
          "\\b(noon)\\b"
              + "|(?<![\\w$.,/+])(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?(?![\\w$%])",
          FLAGS);

  /** What joins two times in one list: "9 or 9:30am", "12, 12:30 or 1pm", "1-3pm". */
  private static final Pattern JOINER =
      Pattern.compile("\\s*(?:,|or|and|to|-|/)\\s*(?:at\\s+)?", FLAGS);

  /** A bare number followed by a unit, a count or a month counts something: "at 15 mins". */
  private static final Pattern COUNTED_AFTER =
      Pattern.compile(
          "^\\s*(?:min(?:ute)?s?|hrs?|hours?|days?|weeks?|months?|years?|%|funds?|members?|"
              + "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\b",
          FLAGS);

  private static final Pattern AT_BEFORE = Pattern.compile("\\bat\\s*$", FLAGS);

  /** A clause that restates a time to decline it is not an offer: "3pm isn't free". */
  private static final Pattern DECLINE =
      Pattern.compile(
          "\\b(?:isn'?t|is not|not available|unavailable|taken|gone|went|booked out|no longer"
              + "|can'?t do)\\b",
          FLAGS);

  /** The time inside a slot label: "Mon 27 Jul 12:00 PM AEST", or "12:00 pm" from en-AU. */
  private static final Pattern LABEL_TIME =
      Pattern.compile("(\\d{1,2}):(\\d{2})\\h?([ap]m)", FLAGS);

  private TimeMentions() {}

  /** A clock time at {@code at} and its possible readings: "9:30" alone is 09:30 or 21:30. */
  record Mention(int at, Set<LocalTime> candidates) {}

  /** A violation when a time the reply offers matches no label's time. */
  public static Optional<Violation> check(String reply, List<String> offered, String booking) {
    Set<LocalTime> allowed = labelTimes(offered, booking);
    boolean unoffered =
        offers(reply).stream()
            .anyMatch(mention -> Collections.disjoint(mention.candidates(), allowed));
    return unoffered
        ? Optional.of(
            new Violation(
                ReplyCheck.TIME_MENTION,
                "a time in the reply is not an offered slot; use only the slot labels"))
        : Optional.empty();
  }

  /** How many different times the reply offers; an ambiguous one counts as its earlier reading. */
  public static long distinctTimes(String reply) {
    return offers(reply).stream()
        .map(mention -> Collections.min(mention.candidates()))
        .distinct()
        .count();
  }

  /**
   * Every clock time in the reply, in order. A later meridiem in the same list proves a bare hour
   * is a time but keeps both its readings: in "11 or 1pm" the 11 is not 11 pm.
   */
  static List<Mention> mentions(String reply) {
    return TIME.matcher(reply).results().flatMap(found -> mention(reply, found).stream()).toList();
  }

  private static Optional<Mention> mention(String reply, MatchResult found) {
    if (found.group(1) != null) {
      return Optional.of(new Mention(found.start(), Set.of(LocalTime.NOON)));
    }
    int hour = Integer.parseInt(found.group(2));
    int minute = found.group(3) == null ? 0 : Integer.parseInt(found.group(3));
    String meridiem = found.group(4);
    if (hour > 23 || minute > 59) {
      return Optional.empty();
    }
    boolean bare = found.group(3) == null && meridiem == null;
    if (bare && !isBareTime(reply.substring(0, found.start()), reply.substring(found.end()))) {
      return Optional.empty();
    }
    return Optional.of(new Mention(found.start(), candidates(hour, minute, meridiem)));
  }

  /** "at 12" and "12, 12:30 or 1pm" are times; "at 15 mins" is not. */
  private static boolean isBareTime(String before, String after) {
    return !COUNTED_AFTER.matcher(after).find()
        && (AT_BEFORE.matcher(before).find() || inTimeList(after));
  }

  /** True when a later time in the same list carries a meridiem: "12, 12:30 or 1pm". */
  private static boolean inTimeList(String after) {
    String rest = after;
    Matcher joiner = JOINER.matcher(rest);
    while (joiner.lookingAt()) {
      Matcher next = TIME.matcher(rest).region(joiner.end(), rest.length());
      if (!next.lookingAt() || next.group(1) != null) {
        return false;
      }
      if (next.group(4) != null) {
        return true;
      }
      rest = rest.substring(next.end());
      joiner = JOINER.matcher(rest);
    }
    return false;
  }

  private static Set<LocalTime> candidates(int hour, int minute, String meridiem) {
    if (meridiem != null) {
      boolean pm = meridiem.toLowerCase(Locale.ROOT).startsWith("p");
      return Set.of(LocalTime.of(hour % 12 + (pm ? 12 : 0), minute));
    }
    if (hour > 12) {
      return Set.of(LocalTime.of(hour, minute));
    }
    return Set.of(LocalTime.of(hour % 12, minute), LocalTime.of(hour % 12 + 12, minute));
  }

  private static Set<LocalTime> labelTimes(List<String> offered, String booking) {
    return Stream.concat(offered.stream(), Stream.ofNullable(booking))
        .flatMap(label -> LABEL_TIME.matcher(label).results().limit(1))
        .map(
            time ->
                LocalTime.of(
                    Integer.parseInt(time.group(1)) % 12
                        + (time.group(3).equalsIgnoreCase("pm") ? 12 : 0),
                    Integer.parseInt(time.group(2))))
        .collect(Collectors.toSet());
  }

  /** Mentions outside a declining clause: restating a taken time is not an offer. */
  private static List<Mention> offers(String reply) {
    return mentions(reply).stream().filter(mention -> !isDeclined(reply, mention.at())).toList();
  }

  private static boolean isDeclined(String reply, int at) {
    return DECLINE
        .matcher(reply.substring(Clauses.start(reply, at), Clauses.end(reply, at)))
        .find();
  }
}
