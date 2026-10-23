package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.config.SettingsStore;
import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptVersion;
import com.enrola.anna.prompt.PromptVersionRepository;
import com.enrola.anna.scheduling.FakeCalendar;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
  @Autowired QuickReplies quickReplies;

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
   * The settings form, saved whole: refused with every problem beside its field and nothing
   * written, or applied, persisted and reread by a store built after it, as at the next boot.
   */
  @Nested
  class RuntimeSettings {

    @Test
    void formShowsTheRunningValuesTheKeysAndWhatNeedsARestart() throws Exception {
      ResultActions shown = mvc.perform(get("/configuration")).andExpect(status().isOk());

      SettingsPage page = settingsPage(shown);
      assertThat(page.form()).isEqualTo(SettingsForm.of(defaults.settings(), quickReplies.text()));
      assertThat(page.problems()).isEmpty();
      assertThat(page.keys())
          .containsExactly(
              new SettingsPage.Key("OPENAI_API_KEY", false),
              new SettingsPage.Key("EVAL_OPENAI_API_KEY", false));
      assertThat(page.restartOnly())
          .extracting(SettingsPage.RestartOnly::name)
          .contains("Database URL", "Model timeout", "Strict model check");
      assertThat(body(shown))
          .contains(
              "name=\"maxTurns\" type=\"number\" value=\"15\"",
              "name=\"prices[0].model\" value=\"gpt-5.1\"",
              "data-key=\"OPENAI_API_KEY\"");
    }

    @Test
    void refusedSaveMarksEveryProblemAndWritesNothing() throws Exception {
      Settings broken =
          SettingsFixture.with(
              SettingsFixture.with(defaults.settings(), "limits", "targetChars", 400),
              "budgets",
              "convCap",
              new BigDecimal("20"));

      ResultActions refused =
          mvc.perform(settingsPost(broken, quickReplies.text())).andExpect(status().isBadRequest());

      SettingsPage page = settingsPage(refused);
      assertThat(page.problems()).containsOnlyKeys("targetChars", "convCap");
      assertThat(page.notice().code()).isEqualTo("SETTINGS_NOT_SAVED");
      assertThat(page.form().targetChars()).isEqualTo(400);
      assertThat(body(refused))
          .contains(
              "data-notice=\"SETTINGS_NOT_SAVED\"",
              "id=\"s-target-error\">" + page.problems().get("targetChars") + "<");
      assertThat(settings.current()).isEqualTo(defaults.settings());
      assertThat(DATABASE.resolve("settings.json")).doesNotExist();
    }

    @Test
    void aValueThatDoesNotBindIsMarkedBeforeAnyRule() throws Exception {
      MultiValueMap<String, String> fields = settingsForm(defaults.settings(), quickReplies.text());
      fields.set("maxChars", "lots");
      fields.set("advisorZone", "Mars/Olympus");

      ResultActions refused =
          mvc.perform(post("/configuration/settings").params(fields))
              .andExpect(status().isBadRequest());

      SettingsPage page = settingsPage(refused);
      assertThat(page.problems()).containsOnlyKeys("maxChars", "advisorZone");
      assertThat(page.typed()).containsEntry("maxChars", "lots");
      assertThat(settings.current()).isEqualTo(defaults.settings());
    }

    /** An emptied number shows empty again, not a zero the browser never sent. */
    @Test
    void anEmptiedNumberIsShownEmptyWithWhatToEnter() throws Exception {
      MultiValueMap<String, String> fields = settingsForm(defaults.settings(), quickReplies.text());
      fields.set("maxTurns", "");

      ResultActions refused =
          mvc.perform(post("/configuration/settings").params(fields))
              .andExpect(status().isBadRequest());

      assertThat(settingsPage(refused).problems())
          .containsEntry("maxTurns", "Enter a whole number.");
      assertThat(body(refused)).contains("name=\"maxTurns\" type=\"number\" value=\"\"");
    }

    /** A bad price cell marks its row, and only that row. */
    @Test
    void aBadPriceCellMarksItsRow() throws Exception {
      MultiValueMap<String, String> fields = settingsForm(defaults.settings(), quickReplies.text());
      fields.set("prices[0].input", "cheap");

      ResultActions refused =
          mvc.perform(post("/configuration/settings").params(fields))
              .andExpect(status().isBadRequest());

      SettingsPage page = settingsPage(refused);
      assertThat(page.problems()).containsOnlyKeys("prices[0]");
      assertThat(page.priceProblem()).isEqualTo("Enter a number.");
    }

    /** A price row the rules refuse is marked by its place in the table, not by its model. */
    @Test
    void aRefusedPriceRowIsMarkedWhereItSits() throws Exception {
      MultiValueMap<String, String> fields = settingsForm(defaults.settings(), quickReplies.text());
      fields.set("prices[0].output", "-1");

      assertThat(
              settingsPage(
                      mvc.perform(post("/configuration/settings").params(fields))
                          .andExpect(status().isBadRequest()))
                  .problems())
          .containsOnlyKeys("prices[0]");
    }

    @Test
    void postsFromAnotherSiteAreRefused() throws Exception {
      for (MockHttpServletRequestBuilder control :
          List.of(
              settingsPost(SettingsFixture.with(defaults.settings(), "limits", "maxTurns", 2), ""),
              post("/configuration/clock")
                  .param("clockMode", "REAL")
                  .param("fixedClock", "2026-07-26T10:00"),
              post("/configuration/calendar-preset").param("calendarPreset", "fully_booked"))) {
        mvc.perform(control.header("Sec-Fetch-Site", "cross-site"))
            .andExpect(status().isForbidden());
      }

      assertThat(settings.current()).isEqualTo(defaults.settings());
    }

    @Test
    void savedSettingsApplyAndAreReadAgainAtBoot() throws Exception {
      Settings changed =
          SettingsFixture.with(defaults.settings(), "limits", "maxTurns", 2)
              .withCalendarPreset(Settings.CalendarPreset.SLOT_TAKEN);

      MvcResult saved =
          mvc.perform(settingsPost(changed, "STOP\nare you a bot?\n"))
              .andExpect(status().is3xxRedirection())
              .andExpect(header().string("Location", "/configuration#settings-form"))
              .andReturn();

      assertThat(saved.getFlashMap().get("settingsNotice"))
          .extracting(notice -> ((Notice) notice).code())
          .isEqualTo("SETTINGS_SAVED");
      assertThat(settings.current()).isEqualTo(changed);
      assertThat(new SettingsStore(defaults, DATABASE).current()).isEqualTo(changed);
      assertThat(quickReplies.load())
          .extracting(QuickReplies.QuickReply::label)
          .containsExactly("STOP", "are you a bot?");
      assertThat(quickReplies.text()).isEqualTo("STOP\nare you a bot?\n");
    }

    @Test
    void quickRepliesOverTheirLimitAreRefused() throws Exception {
      ResultActions refused =
          mvc.perform(settingsPost(defaults.settings(), "chip\n".repeat(51)))
              .andExpect(status().isBadRequest());

      assertThat(settingsPage(refused).problems()).containsOnlyKeys("quickReplies");
      assertThat(DATABASE.resolve("quick-replies.txt")).doesNotExist();
    }

    private SettingsPage settingsPage(ResultActions result) {
      return (SettingsPage) result.andReturn().getModelAndView().getModel().get("settings");
    }
  }

  /**
   * The status strip's clock and calendar controls: each saves one value of the settings, applies
   * at once and outlives a restart, and answers with the page it was pressed on.
   */
  @Nested
  class StripControls {

    @Autowired FakeCalendar calendar;

    @Test
    void clockControlFlipsTheClockAndBack() throws Exception {
      mvc.perform(
              post("/configuration/clock")
                  .param("clockMode", "REAL")
                  .param("fixedClock", "2026-07-26T10:00")
                  .header("Referer", "http://localhost/conversations?q=x"))
          .andExpect(header().string("Location", "/conversations?q=x"));

      assertThat(clock.fixed()).isFalse();
      assertThat(shell(mvc.perform(get("/conversations"))).clockMode())
          .isEqualTo(Settings.ClockMode.REAL);
      assertThat(new SettingsStore(defaults, DATABASE).current().clock().clockMode())
          .isEqualTo(Settings.ClockMode.REAL);

      mvc.perform(
          post("/configuration/clock")
              .param("clockMode", "FIXED")
              .param("fixedClock", "2026-07-27T09:30"));

      assertThat(clock.now()).isEqualTo(Instant.parse("2026-07-26T23:30:00Z"));
      assertThat(shell(mvc.perform(get("/conversations"))).clockTime())
          .isEqualTo(LocalDateTime.of(2026, 7, 27, 9, 30));
    }

    /** The strip always sends a time; a hand-made post without one changes nothing. */
    @Test
    void aClockPostWithoutATimeIs400() throws Exception {
      ResultActions refused =
          mvc.perform(post("/configuration/clock").param("clockMode", "REAL"))
              .andExpect(status().isBadRequest());

      assertThat(config(refused).notice().code()).isEqualTo("BAD_REQUEST");
      assertThat(settings.current()).isEqualTo(defaults.settings());
    }

    @Test
    void presetControlReachesTheCalendar() throws Exception {
      mvc.perform(post("/configuration/calendar-preset").param("calendarPreset", "fully_booked"))
          .andExpect(status().is3xxRedirection());

      assertThat(settings.current().calendarPreset())
          .isEqualTo(Settings.CalendarPreset.FULLY_BOOKED);
      assertThat(
              calendar.freeStarts(
                  clock.now(),
                  clock.now().plusSeconds(7 * 86_400),
                  settings.current().scheduling()))
          .isEmpty();
      assertThat(shell(mvc.perform(get("/conversations"))).calendarPreset())
          .isEqualTo(Settings.CalendarPreset.FULLY_BOOKED);
    }

    @Test
    void anUnknownPresetIs400() throws Exception {
      ResultActions refused =
          mvc.perform(post("/configuration/calendar-preset").param("calendarPreset", "haunted"))
              .andExpect(status().isBadRequest());

      assertThat(config(refused).notice().code()).isEqualTo("BAD_REQUEST");
      assertThat(settings.current().calendarPreset()).isEqualTo(Settings.CalendarPreset.NORMAL);
    }

    private ShellModel shell(ResultActions result) {
      return (ShellModel) result.andReturn().getModelAndView().getModel().get("shell");
    }
  }

  /** The settings form as a browser posts {@code settings}. */
  private static MockHttpServletRequestBuilder settingsPost(
      Settings settings, String quickReplies) {
    return post("/configuration/settings").params(settingsForm(settings, quickReplies));
  }

  /** Every field of the form showing {@code settings}, then the price rows, as a browser sends. */
  private static MultiValueMap<String, String> settingsForm(
      Settings settings, String quickReplies) {
    SettingsForm form = SettingsForm.of(settings, quickReplies);
    MultiValueMap<String, String> fields = new LinkedMultiValueMap<>();
    fields.add("model", form.model());
    fields.add("reasoning", form.reasoning().value());
    fields.add("reasoningSummary", form.reasoningSummary().value());
    fields.add("maxChars", String.valueOf(form.maxChars()));
    fields.add("targetChars", String.valueOf(form.targetChars()));
    fields.add("maxInboundChars", String.valueOf(form.maxInboundChars()));
    fields.add("maxTurns", String.valueOf(form.maxTurns()));
    fields.add("offTopicLimit", String.valueOf(form.offTopicLimit()));
    fields.add("objectionRetries", String.valueOf(form.objectionRetries()));
    fields.add("maxToolRounds", String.valueOf(form.maxToolRounds()));
    fields.add("maxOutputTokens", String.valueOf(form.maxOutputTokens()));
    fields.add("convCap", form.convCap().toPlainString());
    fields.add("appCap", form.appCap().toPlainString());
    fields.add("evalCap", form.evalCap().toPlainString());
    fields.add("persona", form.persona());
    fields.add("company", form.company());
    if (form.disclosureInOpener()) {
      fields.add("disclosureInOpener", "true");
    }
    fields.add("hoursFrom", form.hoursFrom().toString());
    fields.add("hoursTo", form.hoursTo().toString());
    fields.add("satTo", form.satTo().toString());
    fields.add("leadTimeMin", String.valueOf(form.leadTimeMin()));
    fields.add("horizonDays", String.valueOf(form.horizonDays()));
    fields.add("advisorZone", form.advisorZone().getId());
    fields.add("eventMin", String.valueOf(form.eventMin()));
    fields.add("gridMin", String.valueOf(form.gridMin()));
    fields.add("maxSlots", String.valueOf(form.maxSlots()));
    fields.add("stateHolidays", form.stateHolidaysText());
    fields.add("modHarassment", String.valueOf(form.modHarassment()));
    fields.add("modSelfHarm", String.valueOf(form.modSelfHarm()));
    fields.add("modThreat", String.valueOf(form.modThreat()));
    fields.add("clockMode", form.clockMode().name());
    fields.add("fixedClock", form.fixedClock() == null ? "" : form.fixedClock().toString());
    fields.add("calendarPreset", form.calendarPreset().value());
    fields.add("quickReplies", quickReplies);
    List<SettingsForm.PriceRow> rows = form.priceRows();
    for (int row = 0; row < rows.size(); row++) {
      SettingsForm.PriceRow price = rows.get(row);
      fields.add("prices[" + row + "].model", price.model());
      fields.add("prices[" + row + "].input", plain(price.input()));
      fields.add("prices[" + row + "].cached", plain(price.cached()));
      fields.add("prices[" + row + "].output", plain(price.output()));
    }
    return fields;
  }

  private static String plain(BigDecimal usd) {
    return usd == null ? "" : usd.toPlainString();
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
