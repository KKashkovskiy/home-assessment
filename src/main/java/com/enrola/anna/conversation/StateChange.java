package com.enrola.anna.conversation;

/**
 * One field's value before and after a turn; a STATE_DIFF event's payload maps each changed field,
 * by its wire name, to one.
 */
public record StateChange(Object from, Object to) {}
