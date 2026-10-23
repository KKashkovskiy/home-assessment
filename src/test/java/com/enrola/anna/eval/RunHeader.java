package com.enrola.anna.eval;

import java.math.BigDecimal;
import java.time.Instant;

/** What a run's report says about it before any trial, and what its row records. */
record RunHeader(
    String runId,
    String promptVersion,
    String model,
    String effort,
    RunMode mode,
    String selection,
    BigDecimal cap,
    Instant startedAt) {

  /** The key line of the report: which key a keyed run used, never the key. */
  String keyLine() {
    return switch (mode) {
      case RunMode.Keyed keyed -> keyed.keySource();
      case RunMode.Offline _ -> "none (offline)";
      case RunMode.DryRun _, RunMode.NoKey _ -> "none";
    };
  }

  boolean offline() {
    return mode instanceof RunMode.Offline;
  }
}
