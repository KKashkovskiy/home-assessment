package com.enrola.anna.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The app has no login, so this is its cross-site protection, for the console and the platform API
 * alike. Every request must name the app by a local host, so a page whose own name an attacker
 * points at 127.0.0.1 (DNS rebinding) is refused, reads included. A request other than GET is
 * refused unless it comes from the app itself: a browser that sends {@code Sec-Fetch-Site} must say
 * same-origin, or none for a typed URL; one that sends only {@code Origin} must name this host; a
 * request with neither is a client like curl or the SMS platform, not a browser on another site.
 * The API keeps that origin check beside the host check because a page on another site posting to
 * 127.0.0.1 itself names a local host, and only the CORS preflight a JSON body forces would stop it
 * otherwise. No page may be framed, so another site cannot dress a console button up as its own.
 * With the strict same-site session cookie, another site can neither send as the lead nor turn the
 * PII mask off.
 */
@Configuration
public class CrossSiteGuard implements HandlerInterceptor, WebMvcConfigurer {

  /** The names the app answers to; any other is a page reaching it by DNS rebinding. */
  private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

  /** Fixed words, so the refused host or origin is never echoed back. */
  private static final ApiError REFUSED =
      new ApiError(
          "FORBIDDEN", "request must name a local host and not come from another site", null);

  private final JsonMapper json;

  public CrossSiteGuard(JsonMapper json) {
    this.json = json;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this);
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws IOException {
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
    if (LOCAL_HOSTS.contains(request.getServerName())
        && ("GET".equals(request.getMethod()) || fromThisOrigin(request))) {
      return true;
    }
    refuse(request, response);
    return false;
  }

  private static boolean fromThisOrigin(HttpServletRequest request) {
    String fetchSite = request.getHeader("Sec-Fetch-Site");
    if (fetchSite != null) {
      return fetchSite.equals("same-origin") || fetchSite.equals("none");
    }
    String origin = request.getHeader("Origin");
    return origin == null || origin.equals(request.getScheme() + "://" + request.getHeader("Host"));
  }

  /**
   * The API, by its decoded path as routing reads it, answers in its own error shape; a console
   * page gets a bare 403, never a page.
   */
  private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.FORBIDDEN.value());
    if (UrlPathHelper.defaultInstance.getPathWithinApplication(request).startsWith("/api/")) {
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      json.writeValue(response.getOutputStream(), REFUSED);
    }
  }
}
