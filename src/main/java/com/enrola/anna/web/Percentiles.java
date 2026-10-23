package com.enrola.anna.web;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Percentiles and the median over a sorted copy of a small list, in Java rather than SQL so both
 * read the same and test exactly. Empty input gives empty, never an exception.
 */
public final class Percentiles {

  private Percentiles() {}

  /**
   * Nearest rank, {@code p} in (0, 100]: the value at rank ceil(p n / 100), so p95 of ten values is
   * the tenth.
   */
  public static OptionalLong of(List<Long> values, int p) {
    if (p <= 0 || p > 100) {
      throw new IllegalArgumentException("a percentile is over 0 and at most 100, not " + p);
    }
    if (values.isEmpty()) {
      return OptionalLong.empty();
    }
    long[] sorted = sorted(values);
    int rank = (int) (((long) p * sorted.length + 99) / 100);
    return OptionalLong.of(sorted[rank - 1]);
  }

  /** The middle value, or the mean of the two middle values for an even count. */
  public static OptionalDouble median(List<Long> values) {
    if (values.isEmpty()) {
      return OptionalDouble.empty();
    }
    long[] sorted = sorted(values);
    int n = sorted.length;
    return OptionalDouble.of(
        n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0);
  }

  private static long[] sorted(List<Long> values) {
    return values.stream().mapToLong(Long::longValue).sorted().toArray();
  }
}
