package com.enrola.anna.scheduling;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/** One bookable start. The id is opaque to the model; the label is lead-local with its zone. */
public record Slot(String slotId, Instant startUtc, String label) {

  /**
   * "Mon 27 Jul 12:00 PM"; the zone's name follows. English, not en-AU: on JDK 25 en-AU writes
   * "Sept", "July" and "pm", which the label format does not allow.
   */
  private static final DateTimeFormatter LABEL =
      DateTimeFormatter.ofPattern("EEE d MMM h:mm a", Locale.ENGLISH);

  /**
   * The slot at {@code start} as a lead in {@code leadZone} sees it: the lead's wall time and the
   * zone's name at that start, so a label after a DST change says so.
   */
  static Slot of(Instant start, ZoneId leadZone) {
    String label =
        start.atZone(leadZone).format(LABEL) + " " + LeadZones.abbreviation(leadZone, start);
    return new Slot(idFor(start), start, label);
  }

  /** Base64url of the epoch minute. Not a secret; just not a time the model can reason about. */
  static String idFor(Instant start) {
    String minute = Long.toString(start.getEpochSecond() / 60, Character.MAX_RADIX);
    return "s"
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(minute.getBytes(StandardCharsets.US_ASCII));
  }
}
