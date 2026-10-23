package com.enrola.anna.eval;

import java.util.Optional;

/**
 * The key a keyed run spends: {@code EVAL_OPENAI_API_KEY}, or {@code OPENAI_API_KEY} only when
 * {@code EVAL_ALLOW_SHARED_KEY=true}, so a runaway suite cannot spend the app's budget unless the
 * owner opts in. Read from the environment, never a file.
 */
final class EvalKey {

  private EvalKey() {}

  static Optional<String> value() {
    Optional<String> own = env("EVAL_OPENAI_API_KEY");
    if (own.isPresent()) {
      return own;
    }
    return "true".equalsIgnoreCase(System.getenv("EVAL_ALLOW_SHARED_KEY"))
        ? env("OPENAI_API_KEY")
        : Optional.empty();
  }

  /** Which variable {@link #value()} came from, for the report; never the key. */
  static Optional<String> source() {
    if (env("EVAL_OPENAI_API_KEY").isPresent()) {
      return Optional.of("eval (EVAL_OPENAI_API_KEY)");
    }
    return value().map(_ -> "shared (OPENAI_API_KEY, EVAL_ALLOW_SHARED_KEY=true)");
  }

  private static Optional<String> env(String name) {
    return Optional.ofNullable(System.getenv(name)).filter(value -> !value.isBlank());
  }
}
