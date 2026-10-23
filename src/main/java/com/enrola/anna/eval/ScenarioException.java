package com.enrola.anna.eval;

import java.nio.file.Path;

/**
 * A scenario the harness cannot run: it breaks the schema or the stop grammar, its id is not its
 * file name, it seeds a slot the calendar cannot produce, it asks a stand-in for a reply it cannot
 * write, or it outruns its scripted lines. A trial that meets one fails on the hard gate: a broken
 * scenario must turn the run red, not pass unproved.
 */
final class ScenarioException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  ScenarioException(String message) {
    super(message);
  }

  ScenarioException(Path file, String message) {
    super(file.getFileName() + ": " + message);
  }
}
