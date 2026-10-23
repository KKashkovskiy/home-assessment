package com.enrola.anna.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The tabs with no page in this build, so the shell's four tabs all answer. */
@Console
@Controller
public class PlaceholderController {

  @GetMapping("/analytics")
  public String analytics(Model model) {
    return placeholder(model, "Analytics");
  }

  @GetMapping("/evals")
  public String evals(Model model) {
    return placeholder(model, "Evals");
  }

  private static String placeholder(Model model, String title) {
    model.addAttribute("title", title);
    return "placeholder";
  }
}
