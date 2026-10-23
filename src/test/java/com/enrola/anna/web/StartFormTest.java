package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.lead.LeadService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Each custom-lead field fails alone, so its message sits beside it and nothing is inserted. */
class StartFormTest {

  @ParameterizedTest(name = "{0}={1}")
  @CsvSource({
    "givenName, '', givenName",
    "phone, +447700900000, phone",
    "phone, 0412 345 678, ''",
    "email, lauren@example.com, ''",
    "email, lauren smith@example.com, email",
    "email, lauren@example, ''",
    "email, nobody, email",
    "state, XYZ, state",
    "state, qld, ''",
    "state, '', ''"
  })
  void fieldFailsAlone(String field, String value, String failed) {
    StartForm form = new StartForm("custom", lead(field, value));

    assertThat(String.join(",", form.errors().keySet())).isEqualTo(failed);
  }

  private static LeadService.CustomLead lead(String field, String value) {
    return new LeadService.CustomLead(
        field.equals("givenName") ? value : "Pat",
        field.equals("phone") ? value : "0412 345 678",
        field.equals("email") ? value : null,
        field.equals("state") ? value : "VIC",
        null,
        null,
        null);
  }
}
