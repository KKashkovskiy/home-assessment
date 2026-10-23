package com.enrola.anna.web;

import java.util.OptionalDouble;

/**
 * One figure on the analytics page: a count over the base it is out of, and the link to the
 * conversations it counted, or null where no list can show exactly that. A rate is numerator over
 * denominator. A scalar keeps an integer in {@code numerator}, the base count in {@code
 * denominator}, so the low n rule works the same way: a median or percentile itself ({@link
 * #TENTHS} of a turn, seconds, milliseconds), or for money the sum over the base in {@link
 * #USD_SCALE} parts of a dollar. {@code better} gives a comparison its direction; {@code
 * hypothesis} is the target line, or null.
 */
public record Kpi(
    String key,
    String label,
    long numerator,
    long denominator,
    Unit unit,
    String href,
    String hypothesis,
    Better better) {

  /** How to read the numerator: see the record's description. */
  public enum Unit {
    RATE,
    COUNT,
    USD,
    SECONDS,
    MILLIS,
    TURNS
  }

  /** Which way a change is an improvement. */
  public enum Better {
    UP,
    DOWN
  }

  /** Below this base a rate is a direction, not a number, and the tile says so. */
  public static final int LOW_N = 30;

  /** Parts of a dollar a money figure is kept in: ten-thousandths, as a model call's cost shows. */
  public static final long USD_SCALE = 10_000;

  /** Parts of a turn a turn count is kept in. */
  public static final long TENTHS = 10;

  /** Numerator over denominator; empty when the base is zero, never NaN. */
  public OptionalDouble rate() {
    return denominator == 0
        ? OptionalDouble.empty()
        : OptionalDouble.of((double) numerator / denominator);
  }

  /**
   * The figure in its unit: a fraction, a count, dollars per base, seconds, milliseconds or turns.
   * Empty when there is no base to measure it on; a count always has a value.
   */
  public OptionalDouble value() {
    return switch (unit) {
      case COUNT -> OptionalDouble.of((double) numerator);
      case RATE -> rate();
      case USD -> measured((double) numerator / denominator / USD_SCALE);
      case TURNS -> measured((double) numerator / TENTHS);
      case SECONDS, MILLIS -> measured((double) numerator);
    };
  }

  public boolean lowN() {
    return denominator < LOW_N;
  }

  /** This figure with a target line. */
  public Kpi withHypothesis(String line) {
    return new Kpi(key, label, numerator, denominator, unit, href, line, better);
  }

  /** This figure improving the other way. */
  public Kpi withBetter(Better direction) {
    return new Kpi(key, label, numerator, denominator, unit, href, hypothesis, direction);
  }

  private OptionalDouble measured(double value) {
    return denominator == 0 ? OptionalDouble.empty() : OptionalDouble.of(value);
  }
}
