package com.enrola.anna.scheduling;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

/**
 * Australian state to zone, and the abbreviation a lead sees on a label: AEST or AEDT for the same
 * zone, by date. Eight states and territories; anything else is the advisor zone, marked as
 * assumed. The names are a table rather than the JDK's locale data, so a label reads the same on
 * every JDK.
 */
public final class LeadZones {

  /** The zone a lead's state resolved to, and whether it was assumed rather than known. */
  public record Resolved(ZoneId zone, boolean assumed) {}

  /** A zone's standard and daylight-saving names; the same name twice for a zone without DST. */
  private record Names(String standard, String daylight) {}

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

  private static final Map<String, Names> NAMES =
      Map.of(
          "Australia/Sydney", new Names("AEST", "AEDT"),
          "Australia/Melbourne", new Names("AEST", "AEDT"),
          "Australia/Hobart", new Names("AEST", "AEDT"),
          "Australia/Brisbane", new Names("AEST", "AEST"),
          "Australia/Adelaide", new Names("ACST", "ACDT"),
          "Australia/Darwin", new Names("ACST", "ACST"),
          "Australia/Perth", new Names("AWST", "AWST"));

  private LeadZones() {}

  /** Case and whitespace tolerant; a missing or unknown state gets {@code advisorZone}. */
  public static Resolved resolve(String state, ZoneId advisorZone) {
    ZoneId zone = state == null ? null : ZONES.get(state.strip().toUpperCase(Locale.ROOT));
    return zone == null ? new Resolved(advisorZone, true) : new Resolved(zone, false);
  }

  /** The zone's name at {@code at}; a zone outside the table shows its offset, such as +13:00. */
  public static String abbreviation(ZoneId zone, Instant at) {
    Names names = NAMES.get(zone.getId());
    if (names == null) {
      return zone.getRules().getOffset(at).getId();
    }
    return zone.getRules().isDaylightSavings(at) ? names.daylight() : names.standard();
  }
}
