package com.enrola.anna.web;

import jakarta.servlet.http.HttpSession;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The console's session toggles: the status strip's PII mask, absent meaning on, and the
 * inspector's raw JSON, absent meaning hidden. Each answers with a redirect back to the page it was
 * pressed on; the inspector's script toggles raw JSON in place and gets 204.
 */
@Console
@Controller
public class ShellController {

  private static final String RAW_JSON_KEY = "rawJson";

  /** A path on this host: a slash, then no second slash or backslash that would name another. */
  private static final Pattern LOCAL_PATH = Pattern.compile("/(?![/\\\\])[\\w/-]*");

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
    return back(referer);
  }

  @PostMapping("/ui/raw-json")
  public RedirectView rawJson(
      @RequestParam boolean on,
      @RequestHeader(value = "Referer", required = false) String referer,
      HttpSession session) {
    session.setAttribute(RAW_JSON_KEY, on);
    return back(referer);
  }

  @PostMapping(path = "/ui/raw-json", headers = "X-Requested-With")
  public ResponseEntity<Void> rawJsonFromScript(@RequestParam boolean on, HttpSession session) {
    session.setAttribute(RAW_JSON_KEY, on);
    return ResponseEntity.noContent().build();
  }

  /** Back to the page the toggle was pressed on, or the list when that page is not this host's. */
  private static RedirectView back(String referer) {
    RedirectView back = new RedirectView(localPath(referer));
    back.setExpandUriTemplateVariables(false);
    return back;
  }

  private static String localPath(String referer) {
    if (referer == null) {
      return ConversationsController.LIST;
    }
    try {
      UriComponents uri = UriComponentsBuilder.fromUriString(referer).build();
      String path = uri.getPath();
      if (path == null || !LOCAL_PATH.matcher(path).matches()) {
        return ConversationsController.LIST;
      }
      return uri.getQuery() == null ? path : path + "?" + uri.getQuery();
    } catch (IllegalArgumentException _) {
      return ConversationsController.LIST;
    }
  }
}
