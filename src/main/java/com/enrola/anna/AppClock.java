package com.enrola.anna;

import com.enrola.anna.config.ClockProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * The one source of "now": the system clock, or an instant it stands still at, as {@code
 * anna.clock} says at boot. Unlike a {@link Clock} bean it can be stopped while the app runs, so a
 * conversation replays on a known day. Nothing else in the app reads the time.
 */
@Component
public final class AppClock {

  private volatile Clock clock;

  public AppClock(ClockProperties props) {
    clock =
        switch (props.mode()) {
          case REAL -> Clock.systemUTC();
          case FIXED -> Clock.fixed(props.fixedAt(), ZoneOffset.UTC);
        };
  }

  public Instant now() {
    return clock.instant();
  }

  /** Stops the clock at {@code at}. */
  public void fixAt(Instant at) {
    clock = Clock.fixed(at, ZoneOffset.UTC);
  }
}
