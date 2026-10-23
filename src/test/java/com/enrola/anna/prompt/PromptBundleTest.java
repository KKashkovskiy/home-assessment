package com.enrola.anna.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.enrola.anna.config.PersonaProperties;
import com.enrola.anna.config.SmsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.node.JsonNodeFactory;

class PromptBundleTest {

  static final PersonaProperties PERSONA = new PersonaProperties("Anna", "Comparato", true);
  static final SmsProperties SMS = new SmsProperties(320, 300, 1600);

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

  @Test
  void unknownPlaceholderIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> bundle("Hi {{knowledge_pack}}", "pack"))
        .withMessage("prompt v1 has unknown placeholder {{knowledge_pack}}");
  }

  private static PromptBundle bundle(String prompt, String knowledge) {
    return new PromptBundle(
        "v1",
        prompt,
        knowledge,
        JsonNodeFactory.instance.objectNode(),
        new Templates(new DefaultResourceLoader(), "classpath:prompts/v1/templates/", PERSONA),
        "canary-1");
  }
}
