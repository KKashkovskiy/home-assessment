package com.enrola.anna.web;

/** What the next message as the lead gets, as the line under the composer says. */
public enum ComposerHint {
  /** The agent answers. */
  READY,
  /** A person is asked to take over; the agent still classifies and may stay silent. */
  HANDED_OFF,
  /** The conversation ended soft: one closing reply at most, then silence. */
  CLOSING_ONLY,
  /** The conversation ended hard or was reset: stored, never answered. */
  SILENT
}
