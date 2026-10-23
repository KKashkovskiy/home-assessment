package com.enrola.anna.web;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts the {@link ShellModel} on the model of every {@link Console} controller as {@code shell}.
 */
@ControllerAdvice(annotations = Console.class)
public class ShellModelAdvice {

  private final ShellModels shells;

  public ShellModelAdvice(ShellModels shells) {
    this.shells = shells;
  }

  @ModelAttribute("shell")
  public ShellModel shell(HttpSession session) {
    return shells.shell(session);
  }
}
