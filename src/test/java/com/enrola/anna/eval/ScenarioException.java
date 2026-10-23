package com.enrola.anna.eval;

import java.nio.file.Path;

/**
 * A scenario the harness cannot run: it breaks the schema, its id is not its file name, it seeds a
 * slot the calendar cannot produce, or it asks the stand-in for a reply it cannot write. Raised
 * before any model call, so the trial is INVALID, not FAIL: nothing was proved about the prompt.
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
