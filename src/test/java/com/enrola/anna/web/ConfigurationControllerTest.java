package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptVersion;
import com.enrola.anna.prompt.PromptVersionRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The prompt editor over the real store: which version and file open, saves refused with errors or
 * kept with warnings, activation and the pin it moves, refused values, and posts from other sites.
 * Assertions read the page's model, the notice codes and the store; Monaco is not under test.
 */
class ConfigurationControllerTest extends ConsoleFixture {

  static final String MARKER = "ONLY-IN-THE-EDITED-VERSION";

  @Autowired PromptStore prompts;
  @Autowired PromptVersionRepository versions;
  @Autowired DataSource dataSource;

  @Test
  void pageOpensTheActiveVersionInTheForm() throws Exception {
    ResultActions shown =
        mvc.perform(get("/configuration"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Frame-Options", "DENY"));

    ConfigurationPage page = config(shown);
    assertThat(page.selected())
        .extracting(ConfigurationPage.VersionRow::id, ConfigurationPage.VersionRow::active)
        .containsExactly("v1", true);
    assertThat(page.unsaved()).isFalse();
    assertThat(page.canActivate()).isFalse();
    assertThat(page.notice()).isNull();
    assertThat(page.form().file()).isEqualTo(PromptFile.PROMPT);
    assertThat(page.form().text(PromptFile.PROMPT)).isEqualTo(v1().promptMd());
    String body = body(shown);
    assertThat(body)
        .contains(
            "href=\"/configuration\" aria-current=\"page\"",
            "<li data-id=\"v1\" class=\"selected\">",
            "href=\"/configuration?version=v1&amp;file=PROMPT\" aria-current=\"true\"",
            "id=\"settings-form\"")
        .doesNotContain("id=\"prompt-notice\"");
    assertThat(textarea(body, PromptFile.PROMPT)).doesNotContain("hidden");
    assertThat(textarea(body, PromptFile.TOOLS)).contains("hidden");
  }

  @Test
  void pageOpensTheRequestedVersionAndFileNewestFirstOrElseTheActive() throws Exception {
    saveMarked("second");

    ResultActions shown = mvc.perform(get("/configuration?version=v2&file=TOOLS"));

    ConfigurationPage page = config(shown);
    assertThat(page.selected().id()).isEqualTo("v2");
    assertThat(page.form().file()).isEqualTo(PromptFile.TOOLS);
    assertThat(page.canActivate()).isTrue();
    assertThat(page.diffLeft().id()).isEqualTo("v1");
    String body = body(shown);
    assertThat(body.indexOf("data-id=\"v2\"")).isLessThan(body.indexOf("data-id=\"v1\""));
    assertThat(textarea(body, PromptFile.TOOLS)).doesNotContain("hidden");
    assertThat(textarea(body, PromptFile.PROMPT)).contains("hidden", MARKER);
    assertThat(config(mvc.perform(get("/configuration?version=v9"))).selected().id())
        .isEqualTo("v1");
  }

  @Test
  void aValueThePageDoesNotTakeComesBackAsThisPageWithANotice() throws Exception {
    ResultActions badFile =
        mvc.perform(get("/configuration?file=NOPE")).andExpect(status().isBadRequest());
    ResultActions badId =
        mvc.perform(post("/configuration/versions/x1/activate")).andExpect(status().isNotFound());
    ResultActions badPost =
        mvc.perform(save(v1Texts(), "", "NOPE", "v1")).andExpect(status().isBadRequest());

    assertThat(config(badFile).notice().code()).isEqualTo("BAD_REQUEST");
    assertThat(body(badFile)).contains("data-notice=\"BAD_REQUEST\"", "id=\"prompt-form\"");
    assertThat(config(badId).notice().code()).isEqualTo("NOT_FOUND");
    assertThat(body(badId)).contains("data-notice=\"NOT_FOUND\"", "id=\"prompt-form\"");
    assertThat(config(badPost).notice().code()).isEqualTo("BAD_REQUEST");
    assertThat(count("select count(*) from prompt_version")).isEqualTo(1);
  }

  /** A browser posts textarea lines ending CRLF; the version keeps the editor's own line ends. */
  @Test
  void saveCreatesTheNextVersionWholeAndInactive() throws Exception {
    Map<PromptFile, String> texts = v1Texts();
    texts.replaceAll((file, text) -> text.replace("\n", "\r\n"));

    MvcResult saved =
        mvc.perform(save(texts, "wording", "PACK", "v1"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/configuration?version=v2&file=PACK"))
            .andReturn();

    assertThat(flashed(saved))
        .extracting(Notice::level, Notice::code)
        .containsExactly(Notice.Level.PASS, "SAVED");
    PromptVersion v2 = prompts.get("v2");
    assertThat(v2)
        .extracting(PromptVersion::note, PromptVersion::createdBy, PromptVersion::active)
        .containsExactly("wording", "console", false);
    assertThat(v2.promptMd()).isEqualTo(v1().promptMd());
    assertThat(templates(v2)).isEqualTo(templates(v1()));
    assertThat(prompts.activeId()).isEqualTo("v1");
    assertThat(count("select count(*) from prompt_version")).isEqualTo(2);
    ResultActions after =
        mvc.perform(get("/configuration?version=v2&file=PACK").flashAttrs(saved.getFlashMap()));
    assertThat(config(after).notice()).isEqualTo(flashed(saved));
    assertThat(body(after)).contains("data-notice=\"SAVED\"");
  }

  @Test
  void saveRefusedForAMissingPlaceholderKeepsTheDraft() throws Exception {
    Map<PromptFile, String> texts = v1Texts();
    texts.put(PromptFile.PROMPT, texts.get(PromptFile.PROMPT).replace("{{knowledge}}", MARKER));

    ResultActions refused =
        mvc.perform(save(texts, "kept note", "PROMPT", "v1")).andExpect(status().isBadRequest());

    ConfigurationPage page = config(refused);
    assertThat(page.unsaved()).isTrue();
    assertThat(page.canActivate()).isFalse();
    assertThat(page.selected().id()).isEqualTo("v1");
    assertThat(page.notice())
        .extracting(Notice::level, Notice::code)
        .containsExactly(Notice.Level.FAIL, "NOT_SAVED");
    assertThat(page.notice().text()).contains("{{knowledge}}");
    assertThat(page.form().note()).isEqualTo("kept note");
    assertThat(page.form().text(PromptFile.PROMPT)).contains(MARKER);
    String body = body(refused);
    assertThat(body).contains("data-notice=\"NOT_SAVED\"", "data-unsaved=\"true\"");
    assertThat(textarea(body, PromptFile.PROMPT)).contains(MARKER);
    assertThat(count("select count(*) from prompt_version")).isEqualTo(1);
  }

  @Test
  void aRefusedSaveStaysOnTheVersionItStartedFrom() throws Exception {
    saveMarked("second");
    Map<PromptFile, String> texts = v1Texts();
    texts.put(PromptFile.TOOLS, "[]");

    ConfigurationPage page =
        config(mvc.perform(save(texts, "", "TOOLS", "v2")).andExpect(status().isBadRequest()));

    assertThat(page.selected().id()).isEqualTo("v2");
    assertThat(page.unsaved()).isTrue();
    assertThat(page.form().file()).isEqualTo(PromptFile.TOOLS);
  }

  @Test
  void saveRefusedForAMissingToolOrAnAbsentFile() throws Exception {
    Map<PromptFile, String> texts = v1Texts();
    texts.put(PromptFile.TOOLS, texts.get(PromptFile.TOOLS).replace("book_call", "book_cal"));
    assertThat(refusal(texts).text()).contains("book_call");

    texts = v1Texts();
    texts.remove(PromptFile.SCHEMA);
    assertThat(refusal(texts).code()).isEqualTo("NOT_SAVED");
    assertThat(count("select count(*) from prompt_version")).isEqualTo(1);
  }

  /** A template over its cap would not render, so it is an error; GSM-7 is the warning case. */
  @Test
  void saveWithAWarningIsKept() throws Exception {
    Map<PromptFile, String> texts = v1Texts();
    texts.put(PromptFile.TEMPLATES, texts.get(PromptFile.TEMPLATES).replace("it's", "it’s"));

    MvcResult saved =
        mvc.perform(save(texts, " ", "TEMPLATES", "v1"))
            .andExpect(status().is3xxRedirection())
            .andReturn();

    assertThat(flashed(saved))
        .extracting(Notice::level, Notice::code)
        .containsExactly(Notice.Level.WARN, "SAVED");
    assertThat(flashed(saved).text()).contains("GSM-7");
    assertThat(prompts.get("v2").note()).isEqualTo(VersionForm.DEFAULT_NOTE);
  }

  @Test
  void aNoteLongerThanItsColumnIsCutWithAWarning() throws Exception {
    MvcResult saved =
        mvc.perform(save(v1Texts(), "x".repeat(250), "PROMPT", "v1"))
            .andExpect(status().is3xxRedirection())
            .andReturn();

    assertThat(flashed(saved).level()).isEqualTo(Notice.Level.WARN);
    assertThat(prompts.get("v2").note()).hasSize(VersionForm.NOTE_MAX);
  }

  /**
   * Two designers race for v2: another connection holds an uncommitted v2 while this save runs,
   * commits during the save's lock wait, and the save loses on the primary key.
   */
  @Test
  void saveLosingTheIdRaceIs409WithTheDraftKept() throws Exception {
    Map<PromptFile, String> texts = v1Texts();
    texts.put(PromptFile.PROMPT, texts.get(PromptFile.PROMPT) + "\n" + MARKER + "\n");
    ResultActions lost;
    try (Connection other = dataSource.getConnection()) {
      other.setAutoCommit(false);
      try (PreparedStatement insert =
          other.prepareStatement(
              "insert into prompt_version select 'v2', created_at, 'console', 'first', false,"
                  + " prompt_md, knowledge_md, schema_json, tools_json, templates_json"
                  + " from prompt_version where id = 'v1'")) {
        insert.executeUpdate();
      }
      CompletableFuture<Void> commit =
          CompletableFuture.runAsync(
              () -> commit(other), CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS));
      lost = mvc.perform(save(texts, "second", "PROMPT", "v1")).andExpect(status().isConflict());
      commit.join();
    }

    ConfigurationPage page = config(lost);
    assertThat(page.notice().code()).isEqualTo("SAVE_RACE");
    assertThat(page.unsaved()).isTrue();
    assertThat(page.form().text(PromptFile.PROMPT)).contains(MARKER);
    assertThat(page.versions()).hasSize(2);
    assertThat(prompts.get("v2").note()).isEqualTo("first");
  }

  @Test
  void activateMovesNewConversationsOnlyToTheVersion() throws Exception {
    String before = start("lauren");
    saveMarked("second");

    MvcResult activated =
        mvc.perform(post("/configuration/versions/v2/activate").param("file", "TOOLS"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/configuration?version=v2&file=TOOLS"))
            .andReturn();

    assertThat(flashed(activated))
        .extracting(Notice::level, Notice::code)
        .containsExactly(Notice.Level.INFO, "ACTIVATED");
    assertThat(prompts.activeId()).isEqualTo("v2");
    assertThat(pinnedVersion(start("john"))).isEqualTo("v2");
    assertThat(pinnedVersion(before)).isEqualTo("v1");
    MvcResult page = mvc.perform(get("/configuration?version=v2")).andReturn();
    assertThat(((ShellModel) page.getModelAndView().getModel().get("shell")).promptVersion())
        .isEqualTo("v2");
    assertThat(config(page).selected().active()).isTrue();
    assertThat(config(page).canActivate()).isFalse();
  }

  @Test
  void activateAnUnknownVersionIs404() throws Exception {
    ResultActions refused =
        mvc.perform(post("/configuration/versions/v9/activate")).andExpect(status().isNotFound());

    assertThat(config(refused).notice().code()).isEqualTo("NOT_FOUND");
    assertThat(body(refused)).contains("data-notice=\"NOT_FOUND\"");
    assertThat(prompts.activeId()).isEqualTo("v1");
  }

  /** Damage only direct SQL can do: the row is listed as failing and stays inactive. */
  @Test
  void activateAVersionThatCannotLoadIs409AndThePageSaysWhy() throws Exception {
    saveMarked("broken");
    jdbc.update("update prompt_version set schema_json = '{' where id = 'v2'");

    ResultActions refused =
        mvc.perform(post("/configuration/versions/v2/activate")).andExpect(status().isConflict());

    ConfigurationPage page = config(refused);
    assertThat(page.notice())
        .extracting(Notice::level, Notice::code)
        .containsExactly(Notice.Level.FAIL, "LOAD_FAILED");
    assertThat(page.selected().loads()).isFalse();
    assertThat(page.canActivate()).isFalse();
    assertThat(body(refused)).contains("data-notice=\"LOAD_FAILED\"");
    assertThat(prompts.activeId()).isEqualTo("v1");
    assertThat(config(mvc.perform(get("/configuration?version=v2"))).notice())
        .extracting(Notice::level, Notice::code)
        .containsExactly(Notice.Level.WARN, "UNLOADABLE");
    ConfigurationPage onV1 = config(mvc.perform(get("/configuration?version=v1")));
    assertThat(onV1.notice()).isNull();
    assertThat(onV1.versions())
        .extracting(ConfigurationPage.VersionRow::id, ConfigurationPage.VersionRow::loads)
        .containsExactly(tuple("v1", true), tuple("v2", false));
  }

  @Test
  void postsFromAnotherSiteAreRefused() throws Exception {
    mvc.perform(save(v1Texts(), "", "PROMPT", "v1").header("Sec-Fetch-Site", "cross-site"))
        .andExpect(status().isForbidden());
    saveMarked("second");
    mvc.perform(post("/configuration/versions/v2/activate").header("Sec-Fetch-Site", "same-site"))
        .andExpect(status().isForbidden());

    assertThat(count("select count(*) from prompt_version")).isEqualTo(2);
    assertThat(prompts.activeId()).isEqualTo("v1");
  }

  /**
   * The page's form as a browser posts it: each text under its file's field, open on {@code open}.
   */
  private static MockHttpServletRequestBuilder save(
      Map<PromptFile, String> texts, String note, String open, String base) {
    MockHttpServletRequestBuilder form =
        post("/configuration/versions").param("note", note).param("file", open).param("base", base);
    texts.forEach((file, text) -> form.param(file.field(), text));
    return form;
  }

  private Notice refusal(Map<PromptFile, String> texts) throws Exception {
    return config(mvc.perform(save(texts, "", "PROMPT", "v1")).andExpect(status().isBadRequest()))
        .notice();
  }

  private PromptVersion v1() {
    return versions.findById("v1").orElseThrow();
  }

  /** v1's texts as the page shows them. */
  private Map<PromptFile, String> v1Texts() {
    Map<PromptFile, String> texts = new EnumMap<>(PromptFile.class);
    for (PromptFile file : PromptFile.values()) {
      texts.put(file, file.read(v1()));
    }
    return texts;
  }

  /** v1 with one line added to the prompt, saved through the store as the next version. */
  private void saveMarked(String note) {
    PromptVersion v1 = v1();
    assertThat(
            prompts.save(
                new PromptStore.SaveRequest(
                    v1.promptMd() + "\n" + MARKER + "\n",
                    v1.knowledgeMd(),
                    v1.schemaJson(),
                    v1.toolsJson(),
                    v1.templatesJson(),
                    note)))
        .isInstanceOf(PromptStore.SaveResult.Saved.class);
  }

  private String pinnedVersion(String conversationId) {
    return jdbc.queryForObject(
        "select prompt_version from conversation where id = ?", String.class, conversationId);
  }

  private static Map<?, ?> templates(PromptVersion version) {
    return JsonMapper.builder().build().readValue(version.templatesJson(), Map.class);
  }

  private static ConfigurationPage config(ResultActions result) {
    return config(result.andReturn());
  }

  private static ConfigurationPage config(MvcResult result) {
    return (ConfigurationPage) result.getModelAndView().getModel().get("config");
  }

  private static Notice flashed(MvcResult result) {
    return (Notice) result.getFlashMap().get("notice");
  }

  /** The opening tag and text of one file's textarea. */
  private static String textarea(String page, PromptFile file) {
    int start = page.indexOf("<textarea class=\"editor-fallback\" id=\"text-" + file + "\"");
    return page.substring(start, page.indexOf("</textarea>", start));
  }

  private static void commit(Connection connection) {
    try {
      connection.commit();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
