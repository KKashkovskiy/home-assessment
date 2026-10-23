package com.enrola.anna.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where "now" comes from at boot. {@code fixedAt} is read only when {@code mode} is FIXED, so a
 * conversation can be replayed on a known day, and is then required.
 */
@Validated
@ConfigurationProperties(prefix = "anna.clock")
public record ClockProperties(@NotNull Mode mode, Instant fixedAt) {

  /** REAL follows the system clock; FIXED stands still at {@code fixedAt}. */
  public enum Mode {
    REAL,
    FIXED
  }

  /** A fixed clock needs an instant to stand at. */
  @AssertTrue(message = "fixed-at is required when mode is FIXED") public boolean isFixedAtSetWhenFixed() {
    return mode != Mode.FIXED || fixedAt != null;
  }
}
