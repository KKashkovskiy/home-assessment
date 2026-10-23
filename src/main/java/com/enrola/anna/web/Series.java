package com.enrola.anna.web;

import java.util.List;

/**
 * One chart's rows in order, which the script draws and the table view lists. {@code key} is the
 * panel's {@code data-chart} name.
 */
public record Series(String key, List<Row> rows) {

  public Series {
    rows = List.copyOf(rows);
  }

  /** True when a row has a recovered part, so the chart needs its legend. */
  public boolean segmented() {
    return rows.stream().anyMatch(row -> row.segment() != null);
  }

  /**
   * One bar or point: its label, its value and that value as printed, the recovered part of an
   * objection bar, the table's detail line, and the link to the conversations behind it. {@code
   * value} is null for a day with nothing to measure; {@code segment} and {@code href} may be null.
   */
  public record Row(
      String label, Double value, Double segment, String shown, String note, String href) {}
}
