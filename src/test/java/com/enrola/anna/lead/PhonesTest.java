package com.enrola.anna.lead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhonesTest {

  @ParameterizedTest
  @CsvSource({
    "0412 345 678, +61412345678",
    "+61412345678, +61412345678",
    "61412345678, +61412345678",
    "(03) 9123 4567, +61391234567",
  })
  void australianNumbersNormaliseToE164(String raw, String expected) {
    assertThat(Phones.toE164(raw)).isEqualTo(expected);
  }

  @Test
  void nationalDigitsFollowTheCountryCode() {
    assertThat(Phones.nationalDigits("+61412345678")).isEqualTo("412345678");
  }

  @ParameterizedTest
  @ValueSource(strings = {"+44 20 7946 0958", "12345", "0000000000"})
  void otherNumbersAreRejected(String raw) {
    assertThatIllegalArgumentException().isThrownBy(() -> Phones.toE164(raw));
  }
}
