package com.enrola.anna.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.scheduling.LeadZones.Resolved;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LeadZonesTest {

  static final ZoneId MELBOURNE = ZoneId.of("Australia/Melbourne");
  static final ZoneId BRISBANE = ZoneId.of("Australia/Brisbane");
  static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

  @ParameterizedTest
  @CsvSource({
    "NSW, Australia/Sydney",
    "ACT, Australia/Sydney",
    "VIC, Australia/Melbourne",
    "QLD, Australia/Brisbane",
    "SA, Australia/Adelaide",
    "WA, Australia/Perth",
    "TAS, Australia/Hobart",
    "NT, Australia/Darwin",
  })
  void everyStateAndTerritoryResolves(String state, String zone) {
    assertThat(LeadZones.resolve(state, MELBOURNE)).isEqualTo(new Resolved(ZoneId.of(zone), false));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"XX", ""})
  void unknownStateAssumesAdvisorZone(String state) {
    assertThat(LeadZones.resolve(state, MELBOURNE)).isEqualTo(new Resolved(MELBOURNE, true));
  }

  @Test
  void stateIsCaseAndWhitespaceTolerant() {
    assertThat(LeadZones.resolve(" vic ", SYDNEY)).isEqualTo(new Resolved(MELBOURNE, false));
  }

  /** Melbourne moved to AEDT on 4 October 2026 and Brisbane did not: 10:00 there is 09:00 here. */
  @Test
  void brisbaneLeadMelbourneAdvisorAcrossOctoberBoundary() {
    Instant start = LocalDate.of(2026, 10, 5).atTime(10, 0).atZone(MELBOURNE).toInstant();

    assertThat(start.atZone(BRISBANE).toLocalTime()).isEqualTo(LocalTime.of(9, 0));
    assertThat(LeadZones.abbreviation(BRISBANE, start)).isEqualTo("AEST");
    assertThat(LeadZones.abbreviation(MELBOURNE, start)).isEqualTo("AEDT");
  }

  @Test
  void sydneyLeadLabelSwitchesToAedt() {
    assertThat(LeadZones.abbreviation(SYDNEY, sydneyTen(2))).isEqualTo("AEST");
    assertThat(LeadZones.abbreviation(SYDNEY, sydneyTen(5))).isEqualTo("AEDT");
  }

  @Test
  void perthHasNoDaylightName() {
    assertThat(LeadZones.abbreviation(ZoneId.of("Australia/Perth"), sydneyTen(5)))
        .isEqualTo("AWST");
  }

  @Test
  void zoneOutsideTheTableShowsItsOffset() {
    assertThat(LeadZones.abbreviation(ZoneId.of("Pacific/Auckland"), sydneyTen(5)))
        .isEqualTo("+13:00");
  }

  private static Instant sydneyTen(int octoberDay) {
    return LocalDate.of(2026, 10, octoberDay).atTime(10, 0).atZone(SYDNEY).toInstant();
  }
}
