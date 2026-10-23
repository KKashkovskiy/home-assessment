package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Nearest rank for the percentiles, the mean of the middle pair for an even median. */
class PercentilesTest {

  static final List<Long> ONE_TO_TEN = List.of(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);

  @Test
  void p50NearestRank() {
    assertThat(Percentiles.of(ONE_TO_TEN, 50).getAsLong()).isEqualTo(5);
  }

  @Test
  void p95NearestRank() {
    assertThat(Percentiles.of(ONE_TO_TEN, 95).getAsLong()).isEqualTo(10);
  }

  @Test
  void p100IsTheLargestAndARankOutsideTheRangeIsRefused() {
    assertThat(Percentiles.of(ONE_TO_TEN, 100).getAsLong()).isEqualTo(10);
    assertThat(Percentiles.of(List.of(7L), 1).getAsLong()).isEqualTo(7);
    assertThatThrownBy(() -> Percentiles.of(ONE_TO_TEN, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Percentiles.of(ONE_TO_TEN, 101))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void medianEven() {
    assertThat(Percentiles.median(List.of(4L, 6L)).getAsDouble()).isEqualTo(5.0);
  }

  @Test
  void medianOdd() {
    assertThat(Percentiles.median(List.of(3L, 9L, 4L)).getAsDouble()).isEqualTo(4.0);
  }

  @Test
  void empty() {
    assertThat(Percentiles.of(List.of(), 50)).isEmpty();
    assertThat(Percentiles.median(List.of())).isEmpty();
  }
}
