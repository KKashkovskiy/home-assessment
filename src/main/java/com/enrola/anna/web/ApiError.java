package com.enrola.anna.web;

/**
 * Body of every API error. {@code error} is a stable code, {@code message} names the offending
 * field or id, and {@code conversationId} is set only for conflicts.
 */
public record ApiError(String error, String message, String conversationId) {}
