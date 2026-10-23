package com.enrola.anna.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The tab with no page in this build, so the shell's four tabs all answer. */
@Console
@Controller
public class PlaceholderController {

  @GetMapping("/evals")
  public String evals(Model model) {
    model.addAttribute("title", "Evals");
    return "placeholder";
  }
}
