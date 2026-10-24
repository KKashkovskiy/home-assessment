package com.enrola.anna.web;

import com.enrola.anna.config.Settings;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.ModelAndView;

/**
 * The Analytics page: tiles, tables and the version comparison rendered by the server, the charts
 * drawn by the script from {@link AnalyticsDataApi}. The filters and the compared pair are GET
 * parameters, so every view has a URL and the page works without the script; the script only swaps
 * the comparison in place.
 */
@Console
@Controller
public class AnalyticsController {

  static final String PATH = "/analytics";
  private static final String PAGE = "analytics";

  private final AnalyticsPages pages;
  private final AnalyticsService analytics;
  private final ShellModelAdvice shells;

  public AnalyticsController(
      AnalyticsPages pages, AnalyticsService analytics, ShellModelAdvice shells) {
    this.pages = pages;
    this.analytics = analytics;
    this.shells = shells;
  }

  @GetMapping(PATH)
  public String page(
      @ModelAttribute AnalyticsFilter filter,
      @RequestParam(required = false) String a,
      @RequestParam(required = false) String b,
      @ModelAttribute(name = ShellModelAdvice.SETTINGS, binding = false) Settings settings,
      Model model) {
    model.addAttribute("page", pages.page(filter, a, b, settings.scheduling().advisorZone()));
    return PAGE;
  }

  /** The comparison table alone, for the script to swap in when a version select changes. */
  @GetMapping(PATH + "/compare")
  public String compare(
      @ModelAttribute AnalyticsFilter filter,
      @RequestParam @Pattern(regexp = ConfigurationController.VERSION_ID) String a,
      @RequestParam @Pattern(regexp = ConfigurationController.VERSION_ID) String b,
      Model model) {
    model.addAttribute("compare", analytics.compare(a, b, filter));
    return "fragments/compare :: table";
  }

  /**
   * A range or source outside its values, or a compared version that is not a version id, as from a
   * hand-edited link: this page on the default filter, with a notice.
   */
  @ExceptionHandler({
    BindException.class,
    MissingServletRequestParameterException.class,
    HandlerMethodValidationException.class
  })
  ModelAndView badRequest(HttpSession session) {
    Settings settings = shells.settings();
    AnalyticsPage page =
        pages
            .page(
                new AnalyticsFilter(null, null, null, null),
                null,
                null,
                settings.scheduling().advisorZone())
            .withNotice(AnalyticsPages.BAD_REQUEST);
    return new ModelAndView(
        PAGE,
        Map.of("shell", shells.shell(settings, session), "page", page),
        HttpStatus.BAD_REQUEST);
  }
}
