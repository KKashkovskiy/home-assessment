package com.enrola.anna;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.config.SettingsStore;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The clock follows each saved setting: a fixed time is advisor-local, and REAL releases it. */
class AppClockTest {

  @TempDir Path dataDir;

  /** 10:00 in Melbourne in July is midnight UTC. */
  @Test
  void followsEachSavedClock() {
    SettingsStore settings = new SettingsStore(SettingsFixture.DEFAULTS, dataDir);
    AppClock clock = new AppClock(settings);
    assertThat(clock.fixed()).isFalse();

    settings.save(
        SettingsFixture.DEFAULTS.withClock(
            new Settings.Clock(Settings.ClockMode.FIXED, LocalDateTime.of(2026, 7, 26, 10, 0))));

    assertThat(clock.fixed()).isTrue();
    assertThat(clock.now()).isEqualTo(Instant.parse("2026-07-26T00:00:00Z"));

    settings.save(SettingsFixture.DEFAULTS);

    assertThat(clock.fixed()).isFalse();
  }

  /** A scenario that stopped the clock keeps it stopped through a save that leaves the clock. */
  @Test
  void aSaveThatLeavesTheClockLeavesItAlone() {
    SettingsStore settings = new SettingsStore(SettingsFixture.DEFAULTS, dataDir);
    AppClock clock = new AppClock(settings);
    clock.fixAt(Instant.parse("2026-07-27T00:00:00Z"));

    settings.save(
        SettingsFixture.DEFAULTS.withCalendarPreset(Settings.CalendarPreset.FULLY_BOOKED));

    assertThat(clock.now()).isEqualTo(Instant.parse("2026-07-27T00:00:00Z"));
  }
}
