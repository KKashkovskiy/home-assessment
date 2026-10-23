package com.enrola.anna.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The console has no login, so this is its cross-site protection. A page POST is refused with 403
 * unless it comes from the console itself: a browser that sends {@code Sec-Fetch-Site} must say
 * same-origin, or none for a typed URL; one that sends only {@code Origin} must name this host; a
 * request with neither is a client like curl, not a browser on another site. No page may be framed,
 * so another site cannot dress a console button up as its own. With the strict same-site session
 * cookie, another site can neither send as the lead nor turn the PII mask off. The platform API is
 * not covered: the platform calls it without a browser.
 */
@Configuration
public class CrossSiteGuard implements HandlerInterceptor, WebMvcConfigurer {

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(this)
        .addPathPatterns(
            "/", "/conversations/**", "/ui/**", "/configuration", "/analytics", "/evals");
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
    if ("GET".equals(request.getMethod()) || fromThisOrigin(request)) {
      return true;
    }
    response.setStatus(HttpStatus.FORBIDDEN.value());
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
}
