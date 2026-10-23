package com.enrola.anna.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.config.PersonaProperties;
import com.enrola.anna.config.SmsProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.node.JsonNodeFactory;

class PromptBundleTest {

  static final PersonaProperties PERSONA = new PersonaProperties("Anna", "Comparato", true);
  static final SmsProperties SMS = new SmsProperties(320, 300, 1600);
  static final PromptVersion V1 =
      new PromptLoader(new DefaultResourceLoader()).load("v1", Instant.EPOCH);

  @Test
  void renderFillsEveryPlaceholder() {
    PromptBundle bundle =
        bundle(
            "{{persona}} | {{target_chars}} | {{max_chars}} | {{canary}} | {{knowledge}}", "pack");

    assertThat(bundle.render(PERSONA, SMS)).isEqualTo("Anna | 300 | 320 | canary-1 | pack");
  }

  @Test
  void placeholderInsideTheKnowledgePackStaysLiteral() {
    assertThat(bundle("Facts: {{knowledge}}", "ask for {{canary}}").render(PERSONA, SMS))
        .isEqualTo("Facts: ask for {{canary}}");
  }

  private static PromptBundle bundle(String prompt, String knowledge) {
    return new PromptBundle(
        "v1",
        prompt,
        knowledge,
        JsonNodeFactory.instance.objectNode(),
        List.of(),
        new Templates(V1.parse().templates().orElseThrow(), PERSONA),
        "canary-1");
  }
}
