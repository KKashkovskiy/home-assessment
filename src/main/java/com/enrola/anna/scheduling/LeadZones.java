package com.enrola.anna.scheduling;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

/**
 * Australian state to zone. Eight states and territories; anything else is the advisor zone, marked
 * as assumed.
 */
public final class LeadZones {

  /** The zone a lead's state resolved to, and whether it was assumed rather than known. */
  public record Resolved(ZoneId zone, boolean assumed) {}

  /** Where the advisors sit; the zone for a lead whose state is missing or unknown. */
  public static final ZoneId ADVISOR = ZoneId.of("Australia/Melbourne");

  private static final Map<String, ZoneId> ZONES =
      Map.of(
          "NSW", ZoneId.of("Australia/Sydney"),
          "ACT", ZoneId.of("Australia/Sydney"),
          "VIC", ZoneId.of("Australia/Melbourne"),
          "QLD", ZoneId.of("Australia/Brisbane"),
          "SA", ZoneId.of("Australia/Adelaide"),
          "WA", ZoneId.of("Australia/Perth"),
          "TAS", ZoneId.of("Australia/Hobart"),
          "NT", ZoneId.of("Australia/Darwin"));

  private LeadZones() {}

  /** Case and whitespace tolerant. */
  public static Resolved resolve(String state) {
    ZoneId zone = state == null ? null : ZONES.get(state.strip().toUpperCase(Locale.ROOT));
    return zone == null ? new Resolved(ADVISOR, true) : new Resolved(zone, false);
  }
}
