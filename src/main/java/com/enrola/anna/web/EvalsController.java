package com.enrola.anna.web;

import com.enrola.anna.config.Settings;
import com.enrola.anna.web.EvalRunLauncher.Busy;
import com.enrola.anna.web.EvalRunLauncher.Started;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The Evals page: run the suite, edit scenarios and harness files, read reports. Every action is a
 * form post answered with a redirect, or with the page and a notice when it is refused, so the page
 * works without JavaScript; the script posts the same forms and swaps in the parts of the page that
 * came back. {@link EvalsPages} builds the page and words its notices; this class maps outcomes to
 * statuses.
 */
@Console
@Controller
public class EvalsController {

  static final String PATH = "/evals";

  private static final String PAGE = "evals";

  /**
   * Not {@code page}: the layout shows {@code page.notice} as a banner; this page shows its own.
   */
  private static final String MODEL = "evals";

  private static final String RUN_VALUES = "runValues";
  private static final String RUN_NOTICE = "runNotice";
  private static final String EDITOR_NOTICE = "editorNotice";
  private static final String HARNESS_NOTICE = "harnessNotice";

  private final EvalsPages pages;
  private final EvalRunLauncher launcher;
  private final ScenarioFiles files;

  public EvalsController(EvalsPages pages, EvalRunLauncher launcher, ScenarioFiles files) {
    this.pages = pages;
    this.launcher = launcher;
    this.files = files;
  }

  /**
   * {@code runValues}, {@code runNotice}, {@code editorNotice} and {@code harnessNotice} are a
   * redirect's flash: the run form as posted, and the notices.
   */
  @GetMapping(PATH)
  public ModelAndView page(
      @Valid EvalsQuery query,
      @ModelAttribute(name = ShellModelAdvice.SETTINGS, binding = false) Settings settings,
      Model model,
      HttpSession session) {
    EvalsPage page = pages.page(query, PiiMask.enabled(session), settings);
    if (model.getAttribute(RUN_VALUES) instanceof RunRequest posted) {
      page = page.withRunForm(page.runForm().withValues(posted));
    }
    if (model.getAttribute(RUN_NOTICE) instanceof Notice notice) {
      page = page.withRunForm(page.runForm().withNotice(notice));
    }
    if (model.getAttribute(EDITOR_NOTICE) instanceof Notice notice && page.editor() != null) {
      page = page.withEditor(page.editor().withNotice(notice));
    }
    if (model.getAttribute(HARNESS_NOTICE) instanceof Notice notice) {
      page = page.withHarness(page.harness().withNotice(notice));
    }
    return view(page, HttpStatus.OK);
  }

  /**
   * Starts a run as the form asks, or only {@code scenario} when its row's or the editor's button
   * sent it. A run already going is a 409, a value the form does not take a 400, both with the form
   * as sent; started, the page comes back with the form as posted.
   */
  @PostMapping(PATH + "/runs")
  public ModelAndView start(
      @Valid @ModelAttribute RunRequest form,
      BindingResult binding,
      @RequestParam(required = false) String scenario,
      RedirectAttributes flash,
      @ModelAttribute(name = ShellModelAdvice.SETTINGS, binding = false) Settings settings,
      HttpSession session) {
    if (binding.hasErrors() || (scenario != null && !scenario.matches(ScenarioFiles.ID))) {
      List<String> problems =
          binding.hasErrors() ? problems(binding) : List.of("scenario is not a scenario id");
      return refusedRun(
          form, EvalsPages.badRun(problems), HttpStatus.BAD_REQUEST, page(session, settings));
    }
    RunRequest request = scenario == null ? form : form.only(scenario);
    try {
      return switch (launcher.start(request)) {
        case Started _ -> {
          flash.addFlashAttribute(RUN_VALUES, form);
          yield new ModelAndView("redirect:" + PATH + "#run-panel");
        }
        case Busy busy ->
            refusedRun(
                request,
                EvalsPages.busy(busy.runId()),
                HttpStatus.CONFLICT,
                page(session, settings));
      };
    } catch (IOException e) {
      return refusedRun(
          request,
          EvalsPages.launchFailed(e.getClass().getSimpleName()),
          HttpStatus.INTERNAL_SERVER_ERROR,
          page(session, settings));
    }
  }

  @PostMapping(PATH + "/runs/current/cancel")
  public ModelAndView cancel(RedirectAttributes flash) {
    flash.addFlashAttribute(
        RUN_NOTICE, launcher.cancel() ? EvalsPages.CANCELLING : EvalsPages.NOT_RUNNING);
    return new ModelAndView("redirect:" + PATH + "#run-panel");
  }

  /** One trial's page of a run, turn by turn, with a link to its first failing turn. */
  @GetMapping(PATH + "/runs/{runId}/scenarios/{scenarioId}")
  public ModelAndView trial(
      @PathVariable @Pattern(regexp = EvalReports.RUN_ID) String runId,
      @PathVariable @Pattern(regexp = ScenarioFiles.ID) String scenarioId,
      @RequestParam(defaultValue = "1") @Min(1) int trial,
      HttpSession session) {
    return new ModelAndView(
        "eval-trial",
        Map.of("trial", pages.trial(runId, scenarioId, trial, PiiMask.enabled(session))));
  }

  /** A turn of a run's conversation, read from the run's own database, never the app's. */
  @GetMapping(PATH + "/runs/{runId}/conversations/{conversationId}/turns/{turnNo}")
  public ModelAndView trace(
      @PathVariable @Pattern(regexp = EvalReports.RUN_ID) String runId,
      @PathVariable @Pattern(regexp = ConversationApi.ID) String conversationId,
      @PathVariable int turnNo,
      HttpSession session) {
    EvalsPage.TracePage page = pages.trace(runId, conversationId, turnNo, PiiMask.enabled(session));
    return new ModelAndView(
        "eval-turn",
        Map.of("trace", page, "rawOpen", ShellController.rawJsonShown(session)),
        page.turn() == null ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK);
  }

  /**
   * The editor in {@code view} ({@code form} or {@code yaml}) over what was posted, with a history
   * line added or removed when a row's button asked: nothing is written.
   */
  @PostMapping(PATH + "/scenarios/editor")
  public ModelAndView editor(
      @RequestParam MultiValueMap<String, String> posted,
      @RequestParam(defaultValue = "form") String view,
      @ModelAttribute(name = ShellModelAdvice.SETTINGS, binding = false) Settings settings,
      HttpSession session) {
    ScenarioEditor editor = pages.edited(new ScenarioForm(posted), ScenarioForm.View.of(view));
    return view(page(session, settings).withEditor(editor), HttpStatus.OK);
  }

  /**
   * Saves the editor's scenario to {@code evals/scenarios/<id>.yaml} once it loads as the harness
   * reads it; a new id that a file already has is refused, so a save never overwrites another
   * scenario. Refused, the page comes back at 400 with the edits and every problem.
   */
  @PostMapping(PATH + "/scenarios")
  public ModelAndView save(
      @RequestParam MultiValueMap<String, String> posted,
      RedirectAttributes flash,
      @ModelAttribute(name = ShellModelAdvice.SETTINGS, binding = false) Settings settings,
      HttpSession session) {
    var form = new ScenarioForm(posted);
    List<String> problems =
        form.text()
            .map(text -> files.save(form.id(), form.original(), text))
            .orElseGet(() -> files.problems(form.id(), form.yaml()));
    if (!problems.isEmpty()) {
      return view(
          page(session, settings).withEditor(pages.refused(form, problems)),
          HttpStatus.BAD_REQUEST);
    }
    flash.addFlashAttribute(EDITOR_NOTICE, EvalsPages.saved(form.id()));
    return new ModelAndView("redirect:" + PATH + "?edit=" + form.id() + "#editor-panel");
  }

  /**
   * Saves a harness file once it passes its own check; refused, the page comes back at 400 with the
   * text as typed. A name off the list is a 404.
   */
  @PostMapping(PATH + "/files")
  public ModelAndView saveFile(
      @RequestParam String name,
      @RequestParam String text,
      RedirectAttributes flash,
      @ModelAttribute(name = ShellModelAdvice.SETTINGS, binding = false) Settings settings,
      HttpSession session) {
    List<String> problems = files.saveFile(name, text);
    if (!problems.isEmpty()) {
      return view(
          page(session, settings).withHarness(pages.harnessRefused(name, text, problems)),
          HttpStatus.BAD_REQUEST);
    }
    flash.addFlashAttribute(HARNESS_NOTICE, EvalsPages.fileSaved(name));
    return new ModelAndView("redirect:" + PATH + "?file=" + name + "#harness-panel");
  }

  private static ModelAndView refusedRun(
      RunRequest request, Notice notice, HttpStatus status, EvalsPage page) {
    return view(page.withRunForm(page.runForm().withValues(request).withNotice(notice)), status);
  }

  private EvalsPage page(HttpSession session, Settings settings) {
    return pages.page(EvalsQuery.NONE, PiiMask.enabled(session), settings);
  }

  /** A field's message, never the value sent: it reaches a command line only once valid. */
  private static List<String> problems(BindingResult binding) {
    return binding.getAllErrors().stream()
        .map(error -> error instanceof FieldError field ? message(field) : "the form")
        .distinct()
        .toList();
  }

  private static String message(FieldError error) {
    return error.isBindingFailure()
        ? error.getField() + " is not a value the form takes"
        : error.getDefaultMessage();
  }

  private static ModelAndView view(EvalsPage page, HttpStatus status) {
    return new ModelAndView(PAGE, Map.of(MODEL, page), status);
  }
}
