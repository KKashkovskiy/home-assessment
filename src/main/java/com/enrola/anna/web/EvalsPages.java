package com.enrola.anna.web;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsStore;
import com.enrola.anna.conversation.NotFoundException;
import com.enrola.anna.web.EvalReports.RunRow;
import com.enrola.anna.web.EvalReports.TrialLine;
import com.enrola.anna.web.EvalRunLauncher.RunState;
import com.enrola.anna.web.EvalsPage.HarnessFile;
import com.enrola.anna.web.EvalsPage.Report;
import com.enrola.anna.web.EvalsPage.RunForm;
import com.enrola.anna.web.EvalsPage.RunPanel;
import com.enrola.anna.web.EvalsPage.ScenarioList;
import com.enrola.anna.web.ScenarioFiles.ScenarioRow;
import com.enrola.anna.web.ScenarioForm.View;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Assembles the {@link EvalsPage} and the eval trial and trace pages from the harness files, the
 * run reports, the launcher, the runs' own databases and the settings, and words their notices, so
 * the controller only maps outcomes to statuses.
 */
@Component
public class EvalsPages {

  /** The past runs the page lists, and reads the scenarios' last results from. */
  static final int RECENT_RUNS = 20;

  static final String DEFAULT_PRESET = "core-offline";

  static final Notice NOT_RUNNING =
      new Notice(Notice.Level.WARN, "NOT_RUNNING", "404 NOT_RUNNING: no run is going.");

  static final Notice CANCELLING =
      new Notice(
          Notice.Level.INFO,
          "CANCELLING",
          "Cancelling: the run stops within seconds, and its folder keeps what it wrote.");

  static final Notice DUPLICATED =
      new Notice(
          Notice.Level.INFO,
          "DUPLICATED",
          "A copy under a new id, not saved yet: check the id and the title, then save.");

  static final Notice NOT_SAVED =
      new Notice(
          Notice.Level.FAIL,
          "NOT_SAVED",
          "Not saved, nothing written. Each problem below links to its field.");

  static final Notice NOT_PARSED =
      new Notice(
          Notice.Level.FAIL,
          "NOT_PARSED",
          "The form cannot show this YAML until it loads; the problems are below.");

  private final ScenarioFiles files;
  private final EvalReports reports;
  private final EvalRunLauncher launcher;
  private final EvalDbReadOnly runDatabases;
  private final SettingsStore settings;

  public EvalsPages(
      ScenarioFiles files,
      EvalReports reports,
      EvalRunLauncher launcher,
      EvalDbReadOnly runDatabases,
      SettingsStore settings) {
    this.files = files;
    this.reports = reports;
    this.launcher = launcher;
    this.runDatabases = runDatabases;
    this.settings = settings;
  }

  /** The page as {@code query} asks for it. */
  public EvalsPage page(EvalsQuery query, boolean mask) {
    List<ScenarioRow> rows = files.list();
    List<RunRow> runs = reports.recent(RECENT_RUNS);
    List<String> tags = tags(rows);
    String preset = query.preset() == null ? DEFAULT_PRESET : query.preset();
    return new EvalsPage(
        runForm(preset, RunRequest.PRESETS.get(preset), tags, null),
        new ScenarioList(
            rows.stream().filter(query::keeps).toList(),
            rows.size(),
            tags,
            query.q(),
            query.kind(),
            query.tag(),
            reports.lastResults(runs)),
        launcher.last().map(this::runPanel).orElse(null),
        query.run() == null ? null : report(query.run(), mask),
        editor(query),
        harness(query.file()),
        runs);
  }

  /** The run form holding {@code values} as posted, with {@code notice}. */
  RunForm runForm(RunRequest values, List<String> tags, Notice notice) {
    return runForm("", values, tags, notice);
  }

  /**
   * The editor over the posted form, in {@code view}: the fields as typed, or the YAML as typed;
   * or, when Duplicate sent it, a copy under a free id.
   */
  ScenarioEditor edited(ScenarioForm form, View view) {
    if (!form.duplicating()) {
      return over(form, view);
    }
    String text = form.text().orElse(form.yaml());
    return ScenarioForm.read(text)
        .map(tree -> tree.put("id", files.copyId(form.id())))
        .map(tree -> ScenarioEditor.of("", View.FORM, text, tree).withNotice(DUPLICATED))
        .orElseGet(() -> unparsed(form.original(), form.id(), text));
  }

  /**
   * The posted form back in the view it was saved from, its fields marked with {@code problems}.
   */
  ScenarioEditor refused(ScenarioForm form, List<String> problems) {
    return over(form, form.source()).withProblems(clipped(problems), NOT_SAVED);
  }

  /** {@code text} kept as typed in the harness file's editor, beside why it was not saved. */
  HarnessFile harnessRefused(String name, String text, List<String> problems) {
    return new HarnessFile(files.harnessFiles(), name, text, fileNotSaved(problems));
  }

  EvalsPage.TrialPage trial(String runId, String scenarioId, int trialNo, boolean mask) {
    String html =
        reports
            .trialPageHtml(runId, scenarioId, trialNo, mask)
            .orElseThrow(() -> new NotFoundException("trial page", scenarioId + "#" + trialNo));
    TrialLine trial =
        trials(runId)
            .filter(line -> line.scenario().equals(scenarioId) && line.trialNo() == trialNo)
            .findFirst()
            .orElse(null);
    return new EvalsPage.TrialPage(runId, scenarioId, trialNo, trial, html);
  }

  /** A turn of the run's conversation, beside the trial it belongs to. */
  EvalsPage.TracePage trace(String runId, String conversationId, int turnNo, boolean mask) {
    TrialLine trial =
        trials(runId)
            .filter(line -> conversationId.equals(line.conversationId()))
            .findFirst()
            .orElse(null);
    return new EvalsPage.TracePage(
        runId,
        conversationId,
        turnNo,
        runDatabases.turn(runId, conversationId, turnNo, mask).orElse(null),
        trial);
  }

  static Notice busy(String runId) {
    return new Notice(
        Notice.Level.WARN,
        "BUSY",
        "409 BUSY: run " + runId + " is still going; one run at a time. Cancel it or let it end.");
  }

  static Notice badRun(List<String> problems) {
    return new Notice(
        Notice.Level.FAIL, "BAD_RUN", "400 BAD_RUN: the run was not started:" + lines(problems));
  }

  static Notice launchFailed(String why) {
    return new Notice(
        Notice.Level.FAIL,
        "LAUNCH_FAILED",
        "500 LAUNCH_FAILED: the run could not start (" + why + "); the log has no more.");
  }

  static Notice saved(String id) {
    return new Notice(
        Notice.Level.PASS,
        "SAVED",
        "Saved evals/scenarios/" + id + ".yaml: it loads as the harness reads it.");
  }

  static Notice fileSaved(String name) {
    return new Notice(
        Notice.Level.PASS,
        "FILE_SAVED",
        "Saved "
            + name
            + (name.equals(ScenarioFiles.DENY_LIST)
                ? ": eval runs read it from the next run, the app's guards from its next start."
                : ": the next run reads it."));
  }

  private static Notice fileNotSaved(List<String> problems) {
    return new Notice(
        Notice.Level.FAIL, "FILE_NOT_SAVED", "Not saved, the file is unchanged:" + lines(problems));
  }

  private ScenarioEditor over(ScenarioForm form, View view) {
    if (form.source() == View.FORM) {
      return new ScenarioEditor(
          form.original(),
          view,
          form.text().orElse(form.yaml()),
          form.typed(),
          form.postedHistory(),
          List.of(),
          null);
    }
    return ScenarioForm.read(form.yaml())
        .map(tree -> ScenarioEditor.of(form.original(), view, form.yaml(), tree))
        .map(editor -> withId(editor, form.id()))
        .orElseGet(() -> unparsed(form.original(), form.id(), form.yaml()));
  }

  /** The id field as typed, which the YAML's own id must equal. */
  private static ScenarioEditor withId(ScenarioEditor editor, String id) {
    Map<String, String> values = new LinkedHashMap<>(editor.values());
    values.put("id", id);
    return new ScenarioEditor(
        editor.original(),
        editor.view(),
        editor.yaml(),
        values,
        editor.history(),
        editor.problems(),
        editor.notice());
  }

  /** The harness file {@code name}, or the first one when that names none. */
  private HarnessFile harness(String name) {
    List<String> names = files.harnessFiles();
    String shown = names.contains(name) ? name : names.getFirst();
    return new HarnessFile(names, shown, files.read(shown), null);
  }

  private RunForm runForm(String preset, RunRequest values, List<String> tags, Notice notice) {
    Settings current = settings.current();
    return new RunForm(
        preset,
        values,
        tags,
        current.budgets().evalCap(),
        current.agent().model(),
        evalKeySet(),
        notice);
  }

  /** The panel for {@code state}; once ended, its outcome from what the run left behind. */
  private RunPanel runPanel(RunState state) {
    if (state.running()) {
      return new RunPanel(state, null);
    }
    return new RunPanel(
        state, state.ending().cancelled() ? RunOutcome.CANCELLED : reports.outcome(state.id()));
  }

  private Report report(String runId, boolean mask) {
    Optional<List<TrialLine>> trials = reports.trials(runId);
    return new Report(
        runId,
        reports.row(runId).orElse(null),
        reports.outcome(runId),
        reports.reportHtml(runId, mask).orElse(null),
        trials.orElse(List.of()).stream().filter(TrialLine::failed).toList(),
        trials.isEmpty());
  }

  private Stream<TrialLine> trials(String runId) {
    return reports.trials(runId).orElse(List.of()).stream();
  }

  /** The scenario {@code query} opens, a new one of the kind it names, or none. */
  private ScenarioEditor editor(EvalsQuery query) {
    if (query.edit() != null && files.exists(query.edit())) {
      return opened(query.edit(), files.text(query.edit()));
    }
    if (query.create() != null) {
      return opened("", files.skeleton(query.create()));
    }
    return null;
  }

  private ScenarioEditor opened(String original, String yaml) {
    return ScenarioForm.read(yaml)
        .map(tree -> ScenarioEditor.of(original, View.FORM, yaml, tree))
        .orElseGet(() -> unparsed(original, original, yaml));
  }

  /** YAML that is not a mapping stays in the YAML view, with why it does not load. */
  private ScenarioEditor unparsed(String original, String id, String yaml) {
    return new ScenarioEditor(
        original,
        View.YAML,
        yaml,
        Map.of("id", id),
        List.of(),
        clipped(files.problems(id, yaml)),
        NOT_PARSED);
  }

  /**
   * A keyed run spends {@code EVAL_OPENAI_API_KEY}, or the app's key when the owner opts in, read
   * from the environment the child inherits, as the harness reads it.
   */
  private static boolean evalKeySet() {
    return set("EVAL_OPENAI_API_KEY")
        || ("true".equalsIgnoreCase(System.getenv("EVAL_ALLOW_SHARED_KEY"))
            && set("OPENAI_API_KEY"));
  }

  private static boolean set(String key) {
    String value = System.getenv(key);
    return value != null && !value.isBlank();
  }

  private static List<String> tags(List<ScenarioRow> rows) {
    return rows.stream().flatMap(row -> row.tags().stream()).distinct().sorted().toList();
  }

  private static List<String> clipped(List<String> problems) {
    return problems.stream().map(ConfigurationPages::clip).toList();
  }

  /** Each item on a line of its own, cut to {@link ConfigurationPages#ITEM_MAX}. */
  private static String lines(List<String> items) {
    return items.stream()
        .map(item -> "\n" + ConfigurationPages.clip(item))
        .collect(Collectors.joining());
  }
}
