package com.enrola.anna.prompt;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.enrola.anna.config.PersonaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

class PromptLoaderTest {

  final PromptLoader prompts =
      new PromptLoader(
          new DefaultResourceLoader(),
          new PersonaProperties("Anna", "Comparato", true),
          JsonMapper.builder().build(),
          "v1");

  /** A conversation pinned to another version must not silently get this prompt. */
  @Test
  void versionNotLoadedIsRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> prompts.load("v2"))
        .withMessage("prompt version v2 is not loaded");
  }
}
