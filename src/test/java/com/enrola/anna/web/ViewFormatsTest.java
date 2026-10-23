package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** The analytics formats: a figure with no base is n/a, never NaN, and waits read in two units. */
class ViewFormatsTest {

  private final ViewFormats fmt = new ViewFormats(null);

  @ParameterizedTest
  @EnumSource(value = Kpi.Unit.class, names = "COUNT", mode = EnumSource.Mode.EXCLUDE)
  void figureWithNoBaseIsNotApplicable(Kpi.Unit unit) {
    Kpi empty = new Kpi("k", "K", 0, 0, unit, "/conversations", null, Kpi.Better.UP);

    assertThat(fmt.kpi(empty)).isEqualTo("n/a");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          59     | 59 s
          60     | 1m 0s
          900    | 15m 0s
          3599   | 59m 59s
          3600   | 1h 0m
          86399  | 23h 59m
          86400  | 1d 0h
          183600 | 2d 3h
          """)
  void durationInItsTwoLargestUnits(long seconds, String shown) {
    assertThat(fmt.duration(seconds)).isEqualTo(shown);
  }
}
