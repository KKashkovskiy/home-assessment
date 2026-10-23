package com.enrola.anna.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.scheduling.LeadZones.Resolved;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LeadZonesTest {

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
    assertThat(LeadZones.resolve(state)).isEqualTo(new Resolved(ZoneId.of(zone), false));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"XX", ""})
  void unknownStateIsAdvisorZoneAssumed(String state) {
    assertThat(LeadZones.resolve(state)).isEqualTo(new Resolved(LeadZones.ADVISOR, true));
  }

  @Test
  void stateIsCaseAndWhitespaceTolerant() {
    assertThat(LeadZones.resolve(" vic "))
        .isEqualTo(new Resolved(ZoneId.of("Australia/Melbourne"), false));
  }
}
