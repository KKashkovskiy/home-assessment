package com.enrola.anna.eval;

/**
 * What a run does with its model: plan only, answer with the offline stand-in, call OpenAI on the
 * eval key, or skip every trial because there is no key.
 */
sealed interface RunMode {

  /** Prints the selection and the estimate; no model call, no row. */
  record DryRun() implements RunMode {}

  /** The stand-in model and moderation: $0, proves the harness, not the prompt. */
  record Offline() implements RunMode {}

  /** OpenAI on the key {@code keySource} names; never the key itself. */
  record Keyed(String keySource) implements RunMode {}

  /** No key and not offline: every trial SKIPPED_NO_KEY, the report still written. */
  record NoKey() implements RunMode {}
}
