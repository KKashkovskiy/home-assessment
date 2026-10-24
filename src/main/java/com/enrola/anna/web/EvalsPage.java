package com.enrola.anna.web;

import com.enrola.anna.web.EvalReports.RunRow;
import com.enrola.anna.web.EvalReports.TrialLine;
import com.enrola.anna.web.EvalRunLauncher.RunState;
import com.enrola.anna.web.ScenarioFiles.ScenarioRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Everything the Evals page renders: the run form, the scenario list, the run panel, a report, the
 * scenario editor, the harness file and the past runs. A part the page does not show is null.
 */
public record EvalsPage(
    RunForm runForm,
    ScenarioList scenarios,
    RunPanel run,
    Report report,
    ScenarioEditor editor,
    HarnessFile harness,
    List<RunRow> runs) {

  public EvalsPage {
    runs = List.copyOf(runs);
  }

  /**
   * The run form's values, the preset they came from, and what a run falls back on: the settings'
   * eval cap when no budget is given, the settings' model, and whether a keyed run has a key.
   */
  public record RunForm(
      String preset,
      RunRequest values,
      List<String> tags,
      BigDecimal evalCap,
      String model,
      boolean evalKey,
      Notice notice) {

    public Map<String, RunRequest> presets() {
      return RunRequest.PRESETS;
    }

    /** The scenario ids the form's field holds, as typed back into it. */
    public String scenarioIds() {
      return String.join(", ", values.scenarios());
    }

    /** The form holding {@code posted} as sent, under no preset. */
    RunForm withValues(RunRequest posted) {
      return new RunForm("", posted, tags, evalCap, model, evalKey, notice);
    }

    RunForm withNotice(Notice shown) {
      return new RunForm(preset, values, tags, evalCap, model, evalKey, shown);
    }
  }

  /** The scenario list after its filter, each row with its result in the newest run that ran it. */
  public record ScenarioList(
      List<ScenarioRow> rows,
      int total,
      List<String> tags,
      String q,
      String kind,
      String tag,
      Map<String, String> lastResults) {

    public ScenarioList {
      rows = List.copyOf(rows);
      tags = List.copyOf(tags);
      lastResults = Map.copyOf(lastResults);
    }

    /** PASS, FAIL or another trial status; empty when no listed run ran it. */
    public String lastResult(ScenarioRow row) {
      return lastResults.getOrDefault(row.id(), "");
    }
  }

  /** The running run, or the last one this app started; {@code outcome} is null while it runs. */
  public record RunPanel(RunState state, RunOutcome outcome) {

    public boolean running() {
      return state.running();
    }

    /** How far the bar fills: the latest progress line's share, full once the run ended. */
    public int percent() {
      if (!running()) {
        return 100;
      }
      return state.progress() == null || state.progress().total() == 0
          ? 0
          : state.progress().done() * 100 / state.progress().total();
    }

    /** The bar has nothing to measure yet: Maven builds and the suite boots. */
    public boolean indeterminate() {
      return running() && (state.progress() == null || state.progress().done() == 0);
    }

    /** The full output as one text, a line a line. */
    public String log() {
      return String.join("\n", state.lines());
    }

    /** The lines worth reading as one text: progress, warnings and errors. */
    public String notes() {
      return String.join("\n", state.notes());
    }
  }

  /**
   * A run's report: its row if it wrote one, how it ended, its report rendered, its failed trials,
   * and whether its trial lines would not read, as an older build's may not.
   */
  public record Report(
      String runId,
      RunRow row,
      RunOutcome outcome,
      String html,
      List<TrialLine> failures,
      boolean trialsUnread) {

    public Report {
      failures = List.copyOf(failures);
    }

    public String traceUrl(TrialLine trial) {
      return EvalReports.traceUrl(runId, trial);
    }

    public String trialUrl(TrialLine trial) {
      return trialPageUrl(runId, trial.scenario(), trial.trialNo());
    }
  }

  /** The harness file open in its editor, among every file the page edits. */
  public record HarnessFile(List<String> names, String name, String text, Notice notice) {

    public HarnessFile {
      names = List.copyOf(names);
    }

    /** Monaco's language for the file. */
    public String language() {
      return name.endsWith(".md") ? "markdown" : "yaml";
    }

    /** What a save of this file is checked against. */
    public String hint() {
      return ScenarioFiles.HarnessKind.of(name).hint();
    }

    HarnessFile withNotice(Notice shown) {
      return new HarnessFile(names, name, text, shown);
    }
  }

  /** One trial's page: its report page rendered, its line in trials.jsonl if any, its trace. */
  public record TrialPage(
      String runId, String scenarioId, int trialNo, TrialLine trial, String html) {

    public String traceUrl() {
      return trial == null ? null : EvalReports.traceUrl(runId, trial);
    }
  }

  /**
   * A turn of an eval run's conversation from the run's own database, null when unavailable, and
   * the trial it belongs to, null when no line names its conversation.
   */
  public record TracePage(
      String runId, String conversationId, int turnNo, TurnView turn, TrialLine trial) {

    /** The checks the trial failed on this turn. */
    public List<String> failedHere() {
      return trial == null ? List.of() : trial.failedChecksAt(turnNo);
    }

    public String trialUrl() {
      return trial == null ? null : trialPageUrl(runId, trial.scenario(), trial.trialNo());
    }
  }

  /** Where a trial's page is: the first trial's under the scenario, a later one by number. */
  static String trialPageUrl(String runId, String scenarioId, int trialNo) {
    String url = "/evals/runs/" + runId + "/scenarios/" + scenarioId;
    return trialNo == 1 ? url : url + "?trial=" + trialNo;
  }

  EvalsPage withRunForm(RunForm form) {
    return new EvalsPage(form, scenarios, run, report, editor, harness, runs);
  }

  EvalsPage withEditor(ScenarioEditor shown) {
    return new EvalsPage(runForm, scenarios, run, report, shown, harness, runs);
  }

  EvalsPage withHarness(HarnessFile shown) {
    return new EvalsPage(runForm, scenarios, run, report, editor, shown, runs);
  }
}
