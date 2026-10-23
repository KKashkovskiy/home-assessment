package com.enrola.anna.web;

import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptVersionCorrupt;
import com.enrola.anna.prompt.PromptVersionMissing;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The Configuration page: the prompt editor over {@link PromptStore}. The page is a form holding
 * the selected version's five texts, so saving and activating are plain posts answered with a
 * redirect, and the script mounts Monaco over the form. The store validates and mints ids; {@link
 * ConfigurationPages} builds the page and words its notices; this class maps outcomes to statuses.
 * A request the page cannot take comes back as this page with a notice, not the console's list.
 */
@Console
@Controller
public class ConfigurationController {

  /** A version id as the store mints them; anything else names no version. */
  static final String VERSION_ID = "v[1-9][0-9]{0,8}";

  private static final String PATH = "/configuration";
  private static final String PAGE = "configuration";
  private static final String NOTICE = "notice";

  /**
   * Not {@code page}: the layout shows {@code page.notice} as a banner, and this page shows its
   * notice beside the editor instead.
   */
  private static final String MODEL = "config";

  private final PromptStore prompts;
  private final ConfigurationPages pages;
  private final ShellModels shells;

  public ConfigurationController(
      PromptStore prompts, ConfigurationPages pages, ShellModels shells) {
    this.prompts = prompts;
    this.pages = pages;
    this.shells = shells;
  }

  /** An unknown {@code version} opens the active one; {@code notice} is a redirect's flash. */
  @GetMapping(PATH)
  public ModelAndView page(
      @RequestParam(required = false) String version,
      @RequestParam(defaultValue = PromptFile.DEFAULT) PromptFile file,
      Model model) {
    ConfigurationPage page = pages.stored(version, file);
    Notice flashed = (Notice) model.getAttribute(NOTICE);
    return view(flashed != null ? page.withNotice(flashed) : page, HttpStatus.OK);
  }

  /**
   * One new inactive version from the form's five texts. Refused texts come back at 400 with the
   * store's errors, still in the form; another save taking the same id first is a 409 the same way.
   */
  @PostMapping(PATH + "/versions")
  public ModelAndView save(@ModelAttribute VersionForm form, RedirectAttributes flash) {
    PromptStore.SaveResult result;
    try {
      result = prompts.save(form.toSaveRequest());
    } catch (DuplicateKeyException e) {
      return view(pages.unsaved(form, ConfigurationPages.SAVE_RACE), HttpStatus.CONFLICT);
    }
    return switch (result) {
      case PromptStore.SaveResult.Saved saved -> {
        flash.addFlashAttribute(NOTICE, pages.saved(saved, form));
        yield redirect(saved.id(), form.file());
      }
      case PromptStore.SaveResult.Rejected rejected ->
          view(pages.unsaved(form, ConfigurationPages.notSaved(rejected)), HttpStatus.BAD_REQUEST);
    };
  }

  /** New conversations pin {@code id} from now on; a version that cannot load is refused. */
  @PostMapping(PATH + "/versions/{id}/activate")
  public ModelAndView activate(
      @PathVariable @Pattern(regexp = VERSION_ID) String id,
      @RequestParam(defaultValue = PromptFile.DEFAULT) PromptFile file,
      RedirectAttributes flash) {
    try {
      prompts.activate(id);
    } catch (PromptVersionMissing e) {
      return view(
          pages.stored(null, file).withNotice(ConfigurationPages.NOT_FOUND), HttpStatus.NOT_FOUND);
    } catch (PromptVersionCorrupt e) {
      ConfigurationPage page = pages.stored(id, file);
      return view(page.withNotice(pages.loadFailed(page)), HttpStatus.CONFLICT);
    }
    flash.addFlashAttribute(NOTICE, ConfigurationPages.activated(id));
    return redirect(id, file);
  }

  /** A path segment that cannot be a version id names no version. */
  @ExceptionHandler(HandlerMethodValidationException.class)
  ModelAndView malformedId(HttpSession session) {
    return errorPage(HttpStatus.NOT_FOUND, ConfigurationPages.NOT_FOUND, session);
  }

  /** A file or form value outside what the page offers, as from a hand-edited link. */
  @ExceptionHandler({BindException.class, MethodArgumentTypeMismatchException.class})
  ModelAndView badValue(HttpSession session) {
    return errorPage(HttpStatus.BAD_REQUEST, ConfigurationPages.BAD_REQUEST, session);
  }

  /** Exception handlers skip the advice's model attributes, so the shell is added here. */
  private ModelAndView errorPage(HttpStatus status, Notice notice, HttpSession session) {
    ConfigurationPage page =
        pages.stored(null, PromptFile.valueOf(PromptFile.DEFAULT)).withNotice(notice);
    return new ModelAndView(PAGE, Map.of("shell", shells.shell(session), MODEL, page), status);
  }

  private static ModelAndView view(ConfigurationPage page, HttpStatus status) {
    return new ModelAndView(PAGE, Map.of(MODEL, page), status);
  }

  private static ModelAndView redirect(String id, PromptFile file) {
    return new ModelAndView("redirect:" + PATH + "?version=" + id + "&file=" + file.name());
  }
}
