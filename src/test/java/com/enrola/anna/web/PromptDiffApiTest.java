package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enrola.anna.config.SettingsFixture;
import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptVersion;
import com.enrola.anna.prompt.PromptVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The diff's two stored texts, and JSON errors for every request it cannot answer. */
class PromptDiffApiTest extends ConsoleFixture {

  static final String MARKER = "ONLY-IN-THE-EDITED-VERSION";
  static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired PromptStore prompts;
  @Autowired PromptVersionRepository versions;

  @Test
  void diffReturnsBothSavedTextsAndTheLanguage() throws Exception {
    save(v1().promptMd() + "\n" + MARKER + "\n", v1().templatesJson());

    mvc.perform(get("/configuration/diff?a=v1&b=v2&file=PROMPT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.a.id").value("v1"))
        .andExpect(jsonPath("$.a.text").value(not(containsString(MARKER))))
        .andExpect(jsonPath("$.b.id").value("v2"))
        .andExpect(jsonPath("$.b.text").value(containsString(MARKER)))
        .andExpect(jsonPath("$.language").value("markdown"));
    mvc.perform(get("/configuration/diff?a=v1&b=v2&file=TOOLS"))
        .andExpect(jsonPath("$.b.text").value(containsString("book_call")))
        .andExpect(jsonPath("$.language").value("json"));
  }

  /** The seed stores templates on one line; the same templates saved formatted are no change. */
  @Test
  void theSameTemplatesDiffAsEqualHoweverTheyWereFormatted() throws Exception {
    String formatted =
        JSON.writerWithDefaultPrettyPrinter()
            .writeValueAsString(JSON.readTree(v1().templatesJson()));
    save(v1().promptMd(), formatted);

    JsonNode diff =
        JSON.readTree(body(mvc.perform(get("/configuration/diff?a=v1&b=v2&file=TEMPLATES"))));

    assertThat(diff.at("/a/text").asString())
        .isEqualTo(diff.at("/b/text").asString())
        .isNotEqualTo(v1().templatesJson())
        .contains("\n  \"OPENER_PROVIDER\"");
  }

  @Test
  void aRequestItCannotAnswerGetsAJsonError() throws Exception {
    for (String query : new String[] {"a=v1&b=v1&file=foo", "a=x1&b=v1&file=PROMPT", "a=v1"}) {
      mvc.perform(get("/configuration/diff?" + query))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.error").value("VALIDATION"))
          .andExpect(jsonPath("$.conversation_id").doesNotExist());
    }
    mvc.perform(get("/configuration/diff?a=v1&b=v9&file=PROMPT"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("NOT_FOUND"))
        .andExpect(jsonPath("$.conversation_id").doesNotExist());
  }

  private PromptVersion v1() {
    return versions.findById("v1").orElseThrow();
  }

  private void save(String prompt, String templatesJson) {
    PromptVersion v1 = v1();
    assertThat(
            prompts.save(
                new PromptStore.SaveRequest(
                    prompt,
                    v1.knowledgeMd(),
                    v1.schemaJson(),
                    v1.toolsJson(),
                    templatesJson,
                    "diff test"),
                SettingsFixture.DEFAULTS.persona()))
        .isInstanceOf(PromptStore.SaveResult.Saved.class);
  }
}
