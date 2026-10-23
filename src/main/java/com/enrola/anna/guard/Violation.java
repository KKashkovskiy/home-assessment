package com.enrola.anna.guard;

/**
 * One failed reply check and a compact note for the repair call; a deny-list note starts with the
 * rule's id. Never carries the reply or a secret.
 */
public record Violation(ReplyCheck check, String note) {}
