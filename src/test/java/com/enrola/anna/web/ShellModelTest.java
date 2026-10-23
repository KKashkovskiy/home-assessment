package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.config.Settings;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The spend meter: its fill rounded, never past full and full when there is no budget at all, and
 * its label never reading $0.00 for a cheap turn.
 */
class ShellModelTest {

  @ParameterizedTest(name = "{0} of {1}: {2}%, {3}")
  @CsvSource({
    "0, 10, 0, $0.00 of $10.00",
    "0.0083, 10, 0, $0.0083 of $10.00",
    "0.00004, 10, 0, $0.0000 of $10.00",
    "2.5, 10, 25, $2.50 of $10.00",
    "12, 10, 100, $12.00 of $10.00",
    "0, 0, 100, $0.00 of $0.00"
  })
  void meterShowsSpendAgainstTheCap(BigDecimal spend, BigDecimal cap, int percent, String label) {
    ShellModel shell =
        new ShellModel(
            Settings.ClockMode.REAL,
            LocalDateTime.of(2026, 7, 26, 10, 0),
            "AEST",
            Settings.CalendarPreset.NORMAL,
            spend,
            cap,
            "gpt-5.1",
            "low",
            "v1",
            AgentStatus.READY,
            true);

    assertThat(shell.spendPercent()).isEqualTo(percent);
    assertThat(shell.meterLabel()).isEqualTo(label);
  }
}
