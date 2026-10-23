package com.enrola.anna.web;

import jakarta.servlet.http.HttpSession;
import java.util.regex.Pattern;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/** The status strip's own control: the PII mask toggle, kept in the session; absent means on. */
@Console
@Controller
public class ShellController {

  /** A path on this host: a slash, then no second slash or backslash that would name another. */
  private static final Pattern LOCAL_PATH = Pattern.compile("/(?![/\\\\])[\\w/-]*");

  /** Back to the page the toggle was pressed on, or the list when that page is not this host's. */
  @PostMapping("/ui/pii-mask")
  public RedirectView piiMask(
      @RequestParam boolean on,
      @RequestHeader(value = "Referer", required = false) String referer,
      HttpSession session) {
    session.setAttribute(PiiMask.SESSION_KEY, on);
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
