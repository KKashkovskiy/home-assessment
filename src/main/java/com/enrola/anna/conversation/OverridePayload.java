package com.enrola.anna.conversation;

/**
 * The payload of an OVERRIDE guard event on a field code owns: what the model asked for and what
 * code kept, as the field's wire values. The event is named after the field.
 */
public record OverridePayload(String model, String code) {}
