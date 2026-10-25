package com.enrola.anna;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsListener;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The one source of "now": the system clock, or an instant it stands still at. It follows the
 * settings' clock from boot and after each save that changes it, and a scenario can stop or release
 * it directly, so a conversation replays on a known day. A save that leaves the settings' clock as
 * it was leaves this one alone. Unlike a {@link Clock} bean it can change while the app runs.
 * Nothing else in the app reads the time.
 */
@Component
public final class AppClock implements SettingsListener {

  private static final Clock SYSTEM = Clock.systemUTC();

  private volatile Clock clock = SYSTEM;

  /** What the settings last asked for: an instant to stand at, or the system clock. */
  private Optional<Instant> followed;

  public Instant now() {
    return clock.instant();
  }

  /** True while the clock stands still. */
  public boolean fixed() {
    return !clock.equals(SYSTEM);
  }

  /** Stops the clock at {@code at}. */
  public void fixAt(Instant at) {
    clock = Clock.fixed(at, ZoneOffset.UTC);
  }

  /** Lets the clock follow the system clock again. */
  public void real() {
    clock = SYSTEM;
  }

  /** A save that asks for what this clock already follows is not followed again. */
  @Override
  public void settingsChanged(Settings settings) {
    Optional<Instant> wanted = standingInstant(settings);
    if (wanted.equals(followed)) {
      return;
    }
    followed = wanted;
    wanted.ifPresentOrElse(this::fixAt, this::real);
  }

  /** A fixed clock's time is advisor-local wall time, placed in the advisors' zone. */
  private static Optional<Instant> standingInstant(Settings settings) {
    Settings.Clock wanted = settings.clock();
    return switch (wanted.clockMode()) {
      case REAL -> Optional.empty();
      case FIXED ->
          Optional.of(wanted.fixedClock().atZone(settings.scheduling().advisorZone()).toInstant());
    };
  }
}
