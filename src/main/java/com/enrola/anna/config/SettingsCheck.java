package com.enrola.anna.config;

import java.util.List;

/**
 * A rule every settings save must pass that {@link SettingsValidation} cannot see from this
 * package, such as whether the active prompt version still renders with a new persona. Every bean
 * that implements it is asked on each save and once at boot, after every bean is built.
 */
public interface SettingsCheck {

  /** Why {@code candidate} must not run; empty when this rule lets it through. */
  List<SettingsValidation.Problem> problems(Settings candidate);
}
