package com.enrola.anna.config;

/**
 * Something that follows the live {@link Settings} as they change, as the clock and the calendar
 * preset do. Every bean that implements it hears the record loaded at boot, then each record that
 * replaces it. Listeners run in no set order, so each reads only the record it is handed.
 */
public interface SettingsListener {

  /**
   * {@code current} now runs. The boot call comes from the store's constructor; every later one
   * runs under the store's lock, so it returns quickly.
   */
  void settingsChanged(Settings current);
}
