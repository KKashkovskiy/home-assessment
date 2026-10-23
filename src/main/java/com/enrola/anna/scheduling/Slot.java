package com.enrola.anna.scheduling;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** One bookable start. The id is opaque to the model; the label is lead-local with its zone. */
public record Slot(String slotId, Instant startUtc, String label) {

  /** Base64url of the epoch minute. Not a secret; just not a time the model can reason about. */
  static String idFor(Instant start) {
    String minute = Long.toString(start.getEpochSecond() / 60, Character.MAX_RADIX);
    return "s"
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(minute.getBytes(StandardCharsets.US_ASCII));
  }
}
