package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.lead.Lead;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpSession;

/** The mask keeps enough to recognise a record and nothing that reaches the person. */
class PiiMaskTest {

  @ParameterizedTest
  @CsvSource({
    "+61412345678, +61••••••678",
    "0412 345 678, 041• ••• 678",
    "(03) 9123 4567, (03) •••• •567"
  })
  void masksPhoneKeepingItsStartAndLastThreeDigits(String phone, String masked) {
    assertThat(PiiMask.maskPhone(phone)).isEqualTo(masked);
  }

  /** An email the mask cannot read is hidden whole, never shown as it is. */
  @ParameterizedTest
  @CsvSource({
    "lauren@example.com, l***@example.com",
    "'lauren\nsmith@example.com', ***",
    "not an email, ***",
    "'lauren@example.com ', ***"
  })
  void masksEmailKeepingFirstCharAndDomainOrHidesIt(String email, String masked) {
    assertThat(PiiMask.maskEmail(email.replace("\\n", "\n"))).isEqualTo(masked);
  }

  @Test
  void freeTextIsMaskedOrHiddenWholeWhenTheConversationCarriesHealthInformation() {
    String summary = "Call her on 0412 345 678, recently diagnosed";

    assertThat(PiiMask.freeText(summary, false))
        .isEqualTo("Call her on 041• ••• 678, recently diagnosed");
    assertThat(PiiMask.freeText(summary, true)).isEqualTo(PiiMask.HIDDEN);
    assertThat(PiiMask.freeText(null, true)).isNull();
  }

  @Test
  void maskTextMasksPhonesAndEmailsInsideAMessage() {
    String masked = PiiMask.maskText("call me on 0412 345 678 or at lauren@example.com, ta");

    assertThat(masked).isEqualTo("call me on 041• ••• 678 or at ***@***, ta");
  }

  @Test
  void nameIsNotMasked() {
    Lead lauren =
        new Lead(
            "lauren",
            "Lauren",
            "+61400000001",
            "lauren@example.com",
            "VIC",
            "Bupa",
            null,
            null,
            null,
            Instant.EPOCH);

    Lead masked = PiiMask.apply(lauren);

    assertThat(masked.givenName()).isEqualTo("Lauren");
    assertThat(masked.phone()).isEqualTo("+61••••••001");
    assertThat(masked.email()).isEqualTo("l***@example.com");
  }

  @Test
  void onlyAnExplicitOffTurnsTheMaskOff() {
    MockHttpSession session = new MockHttpSession();
    assertThat(PiiMask.enabled(session)).isTrue();

    session.setAttribute(PiiMask.SESSION_KEY, "false");
    assertThat(PiiMask.enabled(session)).isTrue();

    session.setAttribute(PiiMask.SESSION_KEY, false);
    assertThat(PiiMask.enabled(session)).isFalse();
  }
}
