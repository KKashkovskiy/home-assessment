package com.enrola.anna.eval;

import java.util.List;
import java.util.Set;

/**
 * One judge question, loaded from {@code evals/rubrics/}: a yes-or-no {@code question} about one
 * turn or the whole transcript ({@code scope}), the scenario tags it applies to, {@code notes} that
 * sharpen the question, and examples for the humans who calibrate it. Severity is for reading the
 * report; every rubric is advisory.
 */
record Rubric(
    String id,
    String question,
    Scope scope,
    Severity severity,
    Set<String> appliesTo,
    String notes,
    Examples examples) {

  /** A turn rubric is asked once per agent turn, with that turn marked; a transcript one once. */
  enum Scope {
    TURN,
    TRANSCRIPT
  }

  enum Severity {
    HARD,
    SOFT
  }

  /** Replies or transcript excerpts that should PASS and should FAIL. */
  record Examples(List<String> pass, List<String> fail) {

    Examples {
      pass = pass == null ? List.of() : pass;
      fail = fail == null ? List.of() : fail;
    }

    static final Examples NONE = new Examples(List.of(), List.of());
  }

  Rubric {
    appliesTo = appliesTo == null ? Set.of() : Set.copyOf(appliesTo);
    examples = examples == null ? Examples.NONE : examples;
  }
}
