package com.enrola.anna.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UrlsTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "comparato.au/privacy, false",
    "www.comparato.au, false",
    "https://comparato.au/privacy-policy, false",
    "you can book via comparato.au any time, false",
    "bit.ly/x, true",
    "https://example.com, true",
    "see example.com.au for details, true",
    "anna@comparato.au, true",
    "thanks.Would, false",
  })
  void offList(String reply, boolean offList) {
    assertThat(Urls.offList(reply)).isEqualTo(offList);
  }
}
