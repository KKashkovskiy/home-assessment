package com.enrola.anna.web;

import java.util.regex.Pattern;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Where a status strip control answers: back to the page it was pressed on, read from the {@code
 * Referer} header, or the conversation list when that page is not this host's, so a redirect can
 * never leave it.
 */
final class Referer {

  /** A path on this host: a slash, then no second slash or backslash that would name another. */
  private static final Pattern LOCAL_PATH = Pattern.compile("/(?![/\\\\])[\\w/-]*");

  private Referer() {}

  static RedirectView backTo(String referer) {
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
