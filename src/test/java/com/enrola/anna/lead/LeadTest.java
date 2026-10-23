package com.enrola.anna.lead;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LeadTest {

  @ParameterizedTest
  @CsvSource(
      nullValues = "null",
      value = {
        "Bupa, true",
        "nib, true",
        "none, false",
        "' NONE ', false",
        "'', false",
        "' ', false",
        "null, false",
      })
  void hasProviderOnlyForAFundName(String provider, boolean expected) {
    Lead lead =
        new Lead(
            "id", "Sam", "+61400000000", null, null, provider, null, null, null, Instant.EPOCH);

    assertThat(lead.hasProvider()).isEqualTo(expected);
  }
}
