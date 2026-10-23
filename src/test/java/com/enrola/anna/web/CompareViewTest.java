package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * How one figure moves between two versions: in its compared unit, read with its direction, and no
 * change when the move is below what the page shows.
 */
class CompareViewTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          RATE   | DOWN | 1   | 5   | 0   | 1   | -20.0  | BETTER
          RATE   | UP   | 1   | 2   | 1   | 2   | 0.0    | SAME
          RATE   | UP   | 10000 | 20000 | 10001 | 20000 | 0.005 | SAME
          COUNT  | DOWN | 1   | 5   | 0   | 1   | -20.0  | BETTER
          COUNT  | DOWN | 0   | 0   | 1   | 3   | 0.0    | NOT_COMPARABLE
          MILLIS | DOWN | 2300 | 13 | 2340 | 13 | 40.0  | SAME
          MILLIS | DOWN | 800 | 13  | 840 | 13  | 40.0   | WORSE
          TURNS  | DOWN | 45  | 6   | 40  | 8   | -0.5   | BETTER
          """)
  void changeAndVerdict(
      Kpi.Unit unit,
      Kpi.Better better,
      long numeratorA,
      long baseA,
      long numeratorB,
      long baseB,
      double change,
      CompareView.Verdict verdict) {
    CompareView.Row row =
        CompareView.row(kpi(unit, better, numeratorA, baseA), kpi(unit, better, numeratorB, baseB));

    assertThat(row.verdict()).isEqualTo(verdict);
    if (verdict != CompareView.Verdict.NOT_COMPARABLE) {
      assertThat(row.change())
          .isCloseTo(unit == Kpi.Unit.RATE ? change / 100 : change, within(1e-9));
    }
  }

  private static Kpi kpi(Kpi.Unit unit, Kpi.Better better, long numerator, long base) {
    return new Kpi("k", "K", numerator, base, unit, null, null, better);
  }
}
