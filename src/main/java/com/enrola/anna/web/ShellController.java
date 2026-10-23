package com.enrola.anna.web;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

/**
 * The console's session toggles: the status strip's PII mask, absent meaning on, and the
 * inspector's raw JSON, absent meaning hidden. Each answers with a redirect back to the page it was
 * pressed on; the inspector's script toggles raw JSON in place and gets 204.
 */
@Console
@Controller
public class ShellController {

  private static final String RAW_JSON_KEY = "rawJson";

  /** Whether this session shows the raw decision JSON under each turn. */
  static boolean rawJsonShown(HttpSession session) {
    return Boolean.TRUE.equals(session.getAttribute(RAW_JSON_KEY));
  }

  @PostMapping("/ui/pii-mask")
  public RedirectView piiMask(
      @RequestParam boolean on,
      @RequestHeader(value = "Referer", required = false) String referer,
      HttpSession session) {
    session.setAttribute(PiiMask.SESSION_KEY, on);
    return Referer.backTo(referer);
  }

  @PostMapping("/ui/raw-json")
  public RedirectView rawJson(
      @RequestParam boolean on,
      @RequestHeader(value = "Referer", required = false) String referer,
      HttpSession session) {
    session.setAttribute(RAW_JSON_KEY, on);
    return Referer.backTo(referer);
  }

  @PostMapping(path = "/ui/raw-json", headers = "X-Requested-With")
  public ResponseEntity<Void> rawJsonFromScript(@RequestParam boolean on, HttpSession session) {
    session.setAttribute(RAW_JSON_KEY, on);
    return ResponseEntity.noContent().build();
  }
}
