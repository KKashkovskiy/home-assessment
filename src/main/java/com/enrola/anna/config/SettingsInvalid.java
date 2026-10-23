package com.enrola.anna.config;

import java.util.List;

/** A save refused as a whole: {@link #problems()} names every value that is wrong. */
public class SettingsInvalid extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<SettingsValidation.Problem> problems;

  public SettingsInvalid(List<SettingsValidation.Problem> problems) {
    super(problems.size() + " invalid settings: " + problems);
    this.problems = List.copyOf(problems);
  }

  public List<SettingsValidation.Problem> problems() {
    return problems;
  }
}
