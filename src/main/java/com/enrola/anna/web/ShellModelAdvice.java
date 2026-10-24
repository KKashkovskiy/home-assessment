package com.enrola.anna.web;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsStore;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Reads the settings once per request of every {@link Console} controller and puts them on the
 * model as {@code settings}, with the {@link ShellModel} built from the same record as {@code
 * shell}, so the strip and the page never show two snapshots. A handler takes the record as
 * {@code @ModelAttribute(name = SETTINGS, binding = false) Settings}. Exception handlers and the
 * JSON endpoints, which these attributes never reach, call {@link #settings} and {@link #shell}
 * themselves.
 */
@ControllerAdvice(annotations = Console.class)
public class ShellModelAdvice {

  /** The model attribute holding the request's settings. */
  public static final String SETTINGS = "settings";

  private final SettingsStore settings;
  private final ShellModels shells;

  public ShellModelAdvice(SettingsStore settings, ShellModels shells) {
    this.settings = settings;
    this.shells = shells;
  }

  @ModelAttribute(SETTINGS)
  public Settings settings() {
    return settings.current();
  }

  @ModelAttribute("shell")
  public ShellModel shell(
      @ModelAttribute(name = SETTINGS, binding = false) Settings current, HttpSession session) {
    return shells.shell(current, session);
  }
}
