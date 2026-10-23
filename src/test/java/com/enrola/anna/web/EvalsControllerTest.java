package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enrola.anna.config.UiProperties;
import com.enrola.anna.conversation.InboundTurns;
import com.enrola.anna.web.EvalReports.TrialLine;
import com.enrola.anna.web.ScenarioFiles.ScenarioRow;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The Evals page over a copy of the eval files and a launcher whose child is a shell sleeping in
 * Maven's place: the list and its filter, runs started, refused and cancelled, the editor's views
 * and saves, harness files, reports with their failing turns, and a turn read from a run's own
 * database. Assertions read the page's model, notice codes and the files.
 */
@Import(EvalsControllerTest.StandInLauncher.class)
class EvalsControllerTest extends ConsoleFixture {

  static final String RUN = "2026-10-22T10-41-03Z";

  /** A run whose database the trace test copies the app's into. */
  static final String TRACED = "2026-10-22T11-00-00Z";

  /** A launcher whose run sleeps two seconds where Maven would run the suite. */
  @TestConfiguration
  static class StandInLauncher {
    @Bean
    @Primary
    EvalRunLauncher standInLauncher() {
      return new EvalRunLauncher(
          new EvalRunLauncher.Command(EVALS, List.of("sh", "-c", "sleep 2", "sh"), "jdbc:h2:mem:"),
          Clock.systemUTC(),
          new UiProperties(true, 30));
    }
  }

  @Autowired EvalRunLauncher launcher;
  @Autowired InboundTurns inboundTurns;

  @BeforeEach
  @AfterEach
  void theRepoScenariosAndNoRunGoing() throws IOException {
    launcher.cancel();
    await()
        .atMost(Duration.ofSeconds(8))
        .until(() -> launcher.last().map(run -> !run.running()).orElse(true));
    try (var files = Files.list(EVALS.resolve("scenarios"))) {
      for (Path file : files.toList()) {
        Files.delete(file);
      }
    }
    copyDirectory(Path.of("evals/scenarios"), EVALS.resolve("scenarios"));
  }

  @Test
  void pageListsTheScenariosTheFilterKeeps() throws Exception {
    ResultActions shown =
        mvc.perform(get("/evals?kind=story&tag=core"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Frame-Options", "DENY"));

    EvalsPage page = evals(shown);
    assertThat(page.scenarios().rows())
        .extracting(ScenarioRow::id)
        .contains("story-lauren-books")
        .allMatch(id -> id.startsWith("story-"));
    assertThat(page.scenarios().total()).isGreaterThan(page.scenarios().rows().size());
    assertThat(page.runForm().preset()).isEqualTo("core-offline");
    assertThat(body(shown))
        .contains("href=\"/evals\" aria-current=\"page\"", "id=\"row-story-lauren-books\"")
        .doesNotContain("id=\"row-turn-stop-keyword\"");
  }

  @Test
  void aRunStartsAsItsRowAsksAndASecondWaitsItsTurn() throws Exception {
    MvcResult started =
        mvc.perform(run("tag", "core").param("scenario", "turn-stop-keyword"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/evals#run-panel"))
            .andReturn();

    assertThat(launcher.last().orElseThrow().request().scenarios())
        .containsExactly("turn-stop-keyword");
    assertThat(((RunRequest) started.getFlashMap().get("runValues")).tag()).isEqualTo("core");
    ResultActions busy = mvc.perform(run("tag", "core")).andExpect(status().isConflict());
    assertThat(evals(busy).runForm().notice().code()).isEqualTo("BUSY");
    assertThat(evals(busy).run().running()).isTrue();
    MvcResult cancelled =
        mvc.perform(post("/evals/runs/current/cancel"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    assertThat(flashed(cancelled, "runNotice").code()).isEqualTo("CANCELLING");
    await().atMost(Duration.ofSeconds(8)).until(() -> !launcher.last().orElseThrow().running());
    assertThat(evals(mvc.perform(get("/evals"))).run().outcome()).isEqualTo(RunOutcome.CANCELLED);
  }

  /** A value the form does not take never reaches a command line; the notice names the field. */
  @Test
  void aValueTheRunFormDoesNotTakeIs400AndStartsNothing() throws Exception {
    var before = launcher.last();

    ResultActions refused =
        mvc.perform(run("tag", "core; rm -rf /", "trials", "0")).andExpect(status().isBadRequest());

    ResultActions badScenario =
        mvc.perform(run("scenario", "../turn-x")).andExpect(status().isBadRequest());

    assertThat(evals(refused).runForm().notice().code()).isEqualTo("BAD_RUN");
    assertThat(evals(refused).runForm().notice().text()).doesNotContain("rm -rf");
    assertThat(evals(badScenario).runForm().notice().code()).isEqualTo("BAD_RUN");
    assertThat(launcher.last()).isEqualTo(before);
    assertThat(
            flashed(mvc.perform(post("/evals/runs/current/cancel")).andReturn(), "runNotice")
                .code())
        .isEqualTo("NOT_RUNNING");
  }

  /** A page reaching the console under another name, by DNS rebinding, reads nothing. */
  @Test
  void aRequestUnderAnotherHostNameIsRefused() throws Exception {
    mvc.perform(get("/evals").header("Host", "evil.example")).andExpect(status().isForbidden());
    mvc.perform(get("/evals").header("Host", "127.0.0.1:18080")).andExpect(status().isOk());
    mvc.perform(get("/evals").header("Host", "localhost:8080")).andExpect(status().isOk());
  }

  @Test
  void aPostFromAnotherSiteIsRefused() throws Exception {
    var before = launcher.last();

    mvc.perform(run("tag", "core").header("Sec-Fetch-Site", "cross-site"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/evals/scenarios").header("Sec-Fetch-Site", "cross-site"))
        .andExpect(status().isForbidden());

    assertThat(launcher.last()).isEqualTo(before);
  }

  /** Switching views posts the form and writes nothing; the file's text comes back as written. */
  @Test
  void theEditorOpensAScenarioAndSwitchesViewsWithoutWriting() throws Exception {
    String file = Files.readString(EVALS.resolve("scenarios/turn-stop-keyword.yaml"));
    ScenarioEditor opened = evals(mvc.perform(get("/evals?edit=turn-stop-keyword"))).editor();

    ScenarioEditor yaml =
        evals(
                mvc.perform(
                    post("/evals/scenarios/editor").params(posted(opened)).param("view", "yaml")))
            .editor();

    assertThat(opened.yamlView()).isFalse();
    assertThat(opened.value("expect.unsubscribed")).isEqualTo("true");
    assertThat(yaml.yamlView()).isTrue();
    assertThat(yaml.yaml()).isEqualTo(file);
    assertThat(Files.readString(EVALS.resolve("scenarios/turn-stop-keyword.yaml"))).isEqualTo(file);
  }

  @Test
  void aSaveThatDoesNotLoadIs400WithEveryProblemAndWritesNothing() throws Exception {
    ScenarioEditor skeleton = evals(mvc.perform(get("/evals?create=turn"))).editor();

    ResultActions refused =
        mvc.perform(post("/evals/scenarios").params(posted(skeleton)))
            .andExpect(status().isBadRequest());

    assertThat(skeleton.isNew()).isTrue();
    assertThat(evals(refused).editor().notice().code()).isEqualTo("NOT_SAVED");
    assertThat(evals(refused).editor().invalid("message")).isTrue();
    assertThat(EVALS.resolve("scenarios/" + skeleton.id() + ".yaml")).doesNotExist();
  }

  @Test
  void aSaveWritesTheFileAndANewIdThatIsTakenIsRefused() throws Exception {
    ScenarioEditor opened = evals(mvc.perform(get("/evals?edit=turn-stop-keyword"))).editor();
    MultiValueMap<String, String> edited = posted(opened);
    edited.set("expect.unsubscribed", "false");

    MvcResult saved =
        mvc.perform(post("/evals/scenarios").params(edited))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/evals?edit=turn-stop-keyword#editor-panel"))
            .andReturn();
    edited.set("original", "");
    ResultActions taken =
        mvc.perform(post("/evals/scenarios").params(edited)).andExpect(status().isBadRequest());

    assertThat(flashed(saved, "editorNotice").code()).isEqualTo("SAVED");
    assertThat(Files.readString(EVALS.resolve("scenarios/turn-stop-keyword.yaml")))
        .contains("unsubscribed: false")
        .doesNotContain("unsubscribed: true");
    assertThat(evals(taken).editor().notice().code()).isEqualTo("NOT_SAVED");
    assertThat(evals(taken).editor().invalid("id")).isTrue();
  }

  @Test
  void aHarnessFileOffTheListIs404AndABrokenOneIsRefused() throws Exception {
    mvc.perform(post("/evals/files").param("name", "pom.xml").param("text", "x"))
        .andExpect(status().isNotFound());
    String rubric = Files.readString(EVALS.resolve("rubrics/J-SMS-STYLE.yaml"));

    ResultActions refused =
        mvc.perform(
                post("/evals/files")
                    .param("name", "rubrics/J-SMS-STYLE.yaml")
                    .param("text", rubric.replace("brochure?", "brochure.")))
            .andExpect(status().isBadRequest());

    assertThat(evals(refused).harness().notice().code()).isEqualTo("FILE_NOT_SAVED");
    assertThat(Files.readString(EVALS.resolve("rubrics/J-SMS-STYLE.yaml"))).isEqualTo(rubric);
  }

  @Test
  void aReportListsTheFailedTrialsWithTheirFirstFailingTurn() throws Exception {
    write(RUN, "report.md", "# Eval run " + RUN + "\n\n| Verdict | FAIL |\n");
    write(RUN, "exit-code", "3\n");
    write(RUN, "trials.jsonl", EvalReportsTest.TRIALS);

    EvalsPage.Report report = evals(mvc.perform(get("/evals?run=" + RUN))).report();

    assertThat(report.outcome()).isEqualTo(RunOutcome.RED);
    assertThat(report.failures()).extracting(TrialLine::label).containsExactly("story-b#2");
    assertThat(report.traceUrl(report.failures().getFirst()))
        .isEqualTo("/evals/runs/" + RUN + "/conversations/c-2/turns/3");
    assertThat(report.html()).contains("<h3>Eval run " + RUN + "</h3>");
  }

  @Test
  void aTrialPageRendersAndAMissingOneIs404() throws Exception {
    write(RUN, "story-b.2.md", "# story-b#2\n\n- **Lead:** hi\n");
    write(RUN, "trials.jsonl", EvalReportsTest.TRIALS);

    ResultActions page =
        mvc.perform(get("/evals/runs/" + RUN + "/scenarios/story-b?trial=2"))
            .andExpect(status().isOk());

    assertThat(body(page)).contains("<strong>Lead:</strong> hi", "Inspect turn 3");
    mvc.perform(get("/evals/runs/" + RUN + "/scenarios/story-c")).andExpect(status().isNotFound());
    mvc.perform(get("/evals/runs/not-a-run/scenarios/story-b")).andExpect(status().isNotFound());
  }

  /** The run's database is a copy of a database with one turn in it, opened read-only. */
  @Test
  void aTurnIsReadFromTheRunsOwnDatabase() throws Exception {
    String id = start("lauren");
    inboundTurns.handle(id, "STOP", null);
    Path database = EVALS.resolve("runs").resolve(TRACED).resolve("db");
    copyAppDatabaseTo(database);
    jdbc.update("delete from trace_event");
    jdbc.update("delete from turn");
    byte[] before = Files.readAllBytes(database.resolveSibling("db.mv.db"));

    ResultActions turn =
        mvc.perform(get("/evals/runs/" + TRACED + "/conversations/" + id + "/turns/1"))
            .andExpect(status().isOk());

    assertThat(body(turn)).contains("id=\"turn-1\"", "STOP");
    assertThat(database.resolveSibling("db.mv.db")).hasBinaryContent(before);
    mvc.perform(get("/evals/runs/" + TRACED + "/conversations/" + id + "/turns/2"))
        .andExpect(status().isNotFound());
    assertThat(
            body(
                mvc.perform(
                        get("/evals/runs/2026-10-22T08-00-00Z/conversations/" + id + "/turns/1"))
                    .andExpect(status().isServiceUnavailable())))
        .contains("data-notice=\"TRACE_UNAVAILABLE\"");
  }

  /** The run form as the core-offline preset fills it, with {@code fields} set over it. */
  private static MockHttpServletRequestBuilder run(String... fields) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("offline", "true");
    form.add("trials", "1");
    form.add("judges", "none");
    form.add("validityThreshold", "0.2");
    for (int i = 0; i < fields.length; i += 2) {
      form.set(fields[i], fields[i + 1]);
    }
    return post("/evals/runs").params(form);
  }

  /** The editor's form as the page renders it, posted back. */
  private static MultiValueMap<String, String> posted(ScenarioEditor editor) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    editor.values().forEach(form::add);
    form.add("original", editor.original());
    form.add("source", editor.view().value());
    form.add("yaml", editor.yaml());
    editor
        .history()
        .forEach(
            row -> {
              form.add("history.speaker", row.speaker());
              form.add("history.text", row.text());
              form.add("history.offered_slots", row.offeredSlots());
            });
    return form;
  }

  private void copyAppDatabaseTo(Path database) throws IOException, SQLException {
    Path script = Files.createTempFile("app-db", ".sql");
    jdbc.execute("SCRIPT TO '" + script + "'");
    Files.createDirectories(database.getParent());
    Files.deleteIfExists(database.resolveSibling("db.mv.db"));
    try (Connection copy = DriverManager.getConnection("jdbc:h2:file:" + database)) {
      copy.createStatement().execute("RUNSCRIPT FROM '" + script + "'");
    }
    Files.delete(script);
  }

  private static EvalsPage evals(ResultActions result) {
    return (EvalsPage) result.andReturn().getModelAndView().getModel().get("evals");
  }

  private static Notice flashed(MvcResult result, String name) {
    return (Notice) result.getFlashMap().get(name);
  }

  private static void write(String runId, String name, String text) throws IOException {
    Path folder = Files.createDirectories(EVALS.resolve("runs").resolve(runId));
    Files.writeString(folder.resolve(name), text);
  }
}
