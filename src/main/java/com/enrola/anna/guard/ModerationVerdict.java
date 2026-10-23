package com.enrola.anna.guard;

/**
 * The category scores the guards read, 0 to 1, never the raw payload: the highest of harassment and
 * harassment/threatening, of violence and violence/graphic, and of the three self-harm categories.
 * When {@code available} is false the endpoint did not answer and the scores mean nothing.
 */
public record ModerationVerdict(
    double harassment, double threat, double selfHarm, boolean available) {

  public static final ModerationVerdict UNAVAILABLE = new ModerationVerdict(0, 0, 0, false);
}
