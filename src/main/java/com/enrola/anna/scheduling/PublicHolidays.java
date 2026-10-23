package com.enrola.anna.scheduling;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;

/**
 * Days the advisors do not call. National dates are data, not an algorithm: the next two years are
 * known, substitute days included (a Christmas or Boxing Day on a weekend moves to the next
 * weekday). Source: Fair Work Ombudsman list of national public holidays, checked October 2026. The
 * list ends with 2027, so a later date counts as a working day. State holidays come from
 * configuration.
 */
public final class PublicHolidays {

  private static final Set<LocalDate> NATIONAL =
      Set.of(
          LocalDate.of(2026, 1, 1),
          LocalDate.of(2026, 1, 26),
          LocalDate.of(2026, 4, 3), // Good Friday
          LocalDate.of(2026, 4, 6), // Easter Monday
          LocalDate.of(2026, 4, 25),
          LocalDate.of(2026, 12, 25),
          LocalDate.of(2026, 12, 26),
          LocalDate.of(2026, 12, 28), // Boxing Day falls on a Saturday
          LocalDate.of(2027, 1, 1),
          LocalDate.of(2027, 1, 26),
          LocalDate.of(2027, 3, 26), // Good Friday
          LocalDate.of(2027, 3, 29), // Easter Monday
          LocalDate.of(2027, 4, 25),
          LocalDate.of(2027, 12, 25),
          LocalDate.of(2027, 12, 26),
          LocalDate.of(2027, 12, 27), // Christmas Day falls on a Saturday
          LocalDate.of(2027, 12, 28)); // Boxing Day falls on a Sunday

  private final Set<LocalDate> state;

  public PublicHolidays(Collection<LocalDate> state) {
    this.state = Set.copyOf(state);
  }

  public boolean isHoliday(LocalDate day) {
    return NATIONAL.contains(day) || state.contains(day);
  }
}
