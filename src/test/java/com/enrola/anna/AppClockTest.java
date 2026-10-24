package com.enrola.anna;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.config.SettingsStore;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The clock follows each saved setting: a fixed time is advisor-local, and REAL releases it. */
class AppClockTest {

  @TempDir Path dataDir;

  /** 10:00 in Melbourne in July is midnight UTC. */
  @Test
  void followsEachSavedClock() {
    AppClock clock = new AppClock();
    SettingsStore settings =
        new SettingsStore(SettingsFixture.DEFAULTS, dataDir, List.of(clock), List.of());
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
    AppClock clock = new AppClock();
    SettingsStore settings =
        new SettingsStore(SettingsFixture.DEFAULTS, dataDir, List.of(clock), List.of());
    clock.fixAt(Instant.parse("2026-07-27T00:00:00Z"));

    settings.save(
        SettingsFixture.DEFAULTS.withCalendarPreset(Settings.CalendarPreset.FULLY_BOOKED));

    assertThat(clock.now()).isEqualTo(Instant.parse("2026-07-27T00:00:00Z"));
  }
}
