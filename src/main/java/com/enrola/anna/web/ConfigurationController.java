package com.enrola.anna.web;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsInvalid;
import com.enrola.anna.config.SettingsStore;
import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptVersionCorrupt;
import com.enrola.anna.prompt.PromptVersionMissing;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/**
 * The Configuration page: the prompt editor over {@link PromptStore} and the runtime settings form
 * over {@link SettingsStore}. The editor is a form holding the selected version's five texts, so
 * saving and activating are plain posts answered with a redirect, and the script mounts Monaco over
 * the form; the settings are one form saved whole the same way, and the status strip's clock and
 * calendar controls save one value of them from any page. The stores validate; {@link
 * ConfigurationPages} and {@link SettingsPages} build the two sections and word their notices; this
 * class maps outcomes to statuses. A request the page cannot take comes back as this page with a
 * notice, not the console's list.
 */
@Console
@Controller
public class ConfigurationController {

  /** A version id as the store mints them; anything else names no version. */
  static final String VERSION_ID = "v[1-9][0-9]{0,8}";

  private static final String PATH = "/configuration";
  private static final String PAGE = "configuration";
  private static final String NOTICE = "notice";
  private static final String SETTINGS_SECTION = "settingsSection";
  private static final String SETTINGS_NOTICE = "settingsNotice";

  /**
   * Not {@code page}: the layout shows {@code page.notice} as a banner, and this page shows its
   * notice beside the editor instead.
   */
  private static final String MODEL = "config";

  private final PromptStore prompts;
  private final ConfigurationPages pages;
  private final SettingsStore settings;
  private final QuickReplies quickReplies;
  private final SettingsPages settingsPages;
  private final ShellModels shells;

  public ConfigurationController(
      PromptStore prompts,
      ConfigurationPages pages,
      SettingsStore settings,
      QuickReplies quickReplies,
      SettingsPages settingsPages,
      ShellModels shells) {
    this.prompts = prompts;
    this.pages = pages;
    this.settings = settings;
    this.quickReplies = quickReplies;
    this.settingsPages = settingsPages;
    this.shells = shells;
  }

  /**
   * An unknown {@code version} opens the active one; {@code notice} and {@code settingsNotice} are
   * a redirect's flash.
   */
  @GetMapping(PATH)
  public ModelAndView page(
      @RequestParam(required = false) String version,
      @RequestParam(defaultValue = PromptFile.DEFAULT) PromptFile file,
      @ModelAttribute(name = ShellModelAdvice.SETTINGS, binding = false) Settings current,
      Model model) {
    ConfigurationPage page = pages.stored(version, file, current.persona());
    Notice flashed = (Notice) model.getAttribute(NOTICE);
    Notice settingsFlashed = (Notice) model.getAttribute(SETTINGS_NOTICE);
    SettingsPage settingsPage = settingsPages.stored(current);
    return view(
        flashed != null ? page.withNotice(flashed) : page,
        settingsFlashed != null ? settingsPage.withNotice(settingsFlashed) : settingsPage,
        HttpStatus.OK);
  }

  /**
   * The whole settings form in one save, applied from the next turn: the record to the store and
   * the quick replies to their file. A form with any problem comes back at 400 with what was typed
   * and every problem beside its field, and nothing is written.
   */
  @PostMapping(PATH + "/settings")
  public ModelAndView saveSettings(
      @ModelAttribute SettingsForm form,
      BindingResult binding,
      RedirectAttributes flash,
      @ModelAttribute(name = ShellModelAdvice.SETTINGS, binding = false) Settings current) {
    Map<String, String> problems = settingsPages.problems(form, binding, settings::problems);
    if (problems.isEmpty()) {
      try {
        settings.save(form.toSettings());
        quickReplies.save(form.quickReplies());
        flash.addFlashAttribute(SETTINGS_NOTICE, SettingsPages.SAVED);
        return new ModelAndView("redirect:" + PATH + "#settings-form");
      } catch (SettingsInvalid e) { // a version activated since the check may not fit this persona
        problems = SettingsPages.byField(form, e.problems());
      }
    }
    return view(
        pages.stored(null, PromptFile.valueOf(PromptFile.DEFAULT), current.persona()),
        settingsPages.refused(form, binding, problems),
        HttpStatus.BAD_REQUEST);
  }

  /**
   * The status strip's clock: REAL follows the system clock, FIXED stands at {@code fixedClock},
   * advisor-local. The strip sends the current minute while the clock is real, so switching to
   * FIXED stops it there. The same save the form makes, so it applies at once and outlives a
   * restart; back to the page it was pressed on.
   */
  @PostMapping(PATH + "/clock")
  public RedirectView clock(
      @RequestParam Settings.ClockMode clockMode,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fixedClock,
      @RequestHeader(value = "Referer", required = false) String referer) {
    settings.update(current -> current.withClock(new Settings.Clock(clockMode, fixedClock)));
    return Referer.backTo(referer);
  }

  /** The status strip's calendar preset, saved as the clock is. */
  @PostMapping(PATH + "/calendar-preset")
  public RedirectView calendarPreset(
      @RequestParam Settings.CalendarPreset calendarPreset,
      @RequestHeader(value = "Referer", required = false) String referer) {
    settings.update(current -> current.withCalendarPreset(calendarPreset));
    return Referer.backTo(referer);
  }

  /**
   * One new inactive version from the form's five texts. Refused texts come back at 400 with the
   * store's errors, still in the form; another save taking the same id first is a 409 the same way.
   */
  @PostMapping(PATH + "/versions")
  public ModelAndView save(
      @ModelAttribute VersionForm form,
      RedirectAttributes flash,
      @ModelAttribute(name = ShellModelAdvice.SETTINGS, binding = false) Settings current) {
    PromptStore.SaveResult result;
    try {
      result = prompts.save(form.toSaveRequest(), current.persona());
    } catch (DuplicateKeyException e) {
      return view(
          pages.unsaved(form, ConfigurationPages.SAVE_RACE, current.persona()),
          settingsPages.stored(current),
          HttpStatus.CONFLICT);
    }
    return switch (result) {
      case PromptStore.SaveResult.Saved saved -> {
        flash.addFlashAttribute(NOTICE, pages.saved(saved, form));
        yield redirect(saved.id(), form.file());
      }
      case PromptStore.SaveResult.Rejected rejected ->
          view(
              pages.unsaved(form, ConfigurationPages.notSaved(rejected), current.persona()),
              settingsPages.stored(current),
              HttpStatus.BAD_REQUEST);
    };
  }

  /**
   * New conversations pin {@code id} from now on; a version that cannot load is refused. The check
   * and the switch hold the settings, so no persona can be saved between them.
   */
  @PostMapping(PATH + "/versions/{id}/activate")
  public ModelAndView activate(
      @PathVariable @Pattern(regexp = VERSION_ID) String id,
      @RequestParam(defaultValue = PromptFile.DEFAULT) PromptFile file,
      RedirectAttributes flash,
      @ModelAttribute(name = ShellModelAdvice.SETTINGS, binding = false) Settings current) {
    try {
      settings.holding(held -> prompts.activate(id, held.persona()));
    } catch (PromptVersionMissing e) {
      return view(
          pages.stored(null, file, current.persona()).withNotice(ConfigurationPages.NOT_FOUND),
          settingsPages.stored(current),
          HttpStatus.NOT_FOUND);
    } catch (PromptVersionCorrupt e) {
      ConfigurationPage page = pages.stored(id, file, current.persona());
      return view(
          page.withNotice(pages.loadFailed(page)),
          settingsPages.stored(current),
          HttpStatus.CONFLICT);
    }
    flash.addFlashAttribute(NOTICE, ConfigurationPages.activated(id));
    return redirect(id, file);
  }

  /** A path segment that cannot be a version id names no version. */
  @ExceptionHandler(HandlerMethodValidationException.class)
  ModelAndView malformedId(HttpSession session) {
    return errorPage(HttpStatus.NOT_FOUND, ConfigurationPages.NOT_FOUND, session);
  }

  /**
   * A file or form value outside what the page offers, as from a hand-edited link, or a strip
   * control's value missing or refused by the store.
   */
  @ExceptionHandler({
    BindException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    SettingsInvalid.class
  })
  ModelAndView badValue(HttpSession session) {
    return errorPage(HttpStatus.BAD_REQUEST, ConfigurationPages.BAD_REQUEST, session);
  }

  /** Exception handlers skip the advice's model attributes, so the shell is added here. */
  private ModelAndView errorPage(HttpStatus status, Notice notice, HttpSession session) {
    Settings current = settings.current();
    ConfigurationPage page =
        pages
            .stored(null, PromptFile.valueOf(PromptFile.DEFAULT), current.persona())
            .withNotice(notice);
    return new ModelAndView(
        PAGE,
        Map.of(
            "shell",
            shells.shell(current, session),
            MODEL,
            page,
            SETTINGS_SECTION,
            settingsPages.stored(current)),
        status);
  }

  private static ModelAndView view(
      ConfigurationPage page, SettingsPage settingsPage, HttpStatus status) {
    return new ModelAndView(PAGE, Map.of(MODEL, page, SETTINGS_SECTION, settingsPage), status);
  }

  private static ModelAndView redirect(String id, PromptFile file) {
    return new ModelAndView("redirect:" + PATH + "?version=" + id + "&file=" + file.name());
  }
}
