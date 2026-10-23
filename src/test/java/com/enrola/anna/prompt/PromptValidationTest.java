package com.enrola.anna.prompt;

import static com.enrola.anna.prompt.PromptValidationTest.Artifact.KNOWLEDGE;
import static com.enrola.anna.prompt.PromptValidationTest.Artifact.PROMPT;
import static com.enrola.anna.prompt.PromptValidationTest.Artifact.SCHEMA;
import static com.enrola.anna.prompt.PromptValidationTest.Artifact.TEMPLATES;
import static com.enrola.anna.prompt.PromptValidationTest.Artifact.TOOLS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.enrola.anna.config.PersonaProperties;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.DefaultResourceLoader;

/** Every rule, each on the repo's v1 with one artifact broken. */
class PromptValidationTest {

  static final PersonaProperties PERSONA = new PersonaProperties("Anna", "Comparato", true);
  static final PromptVersion V1 =
      new PromptLoader(new DefaultResourceLoader()).load("v1", Instant.EPOCH);

  enum Artifact {
    PROMPT,
    KNOWLEDGE,
    SCHEMA,
    TOOLS,
    TEMPLATES
  }

  /** Pins the repo's own files to the rules. */
  @Test
  void repoV1IsClean() {
    PromptValidation.Result result = PromptValidation.validate(V1, PERSONA);

    assertThat(result.errors()).isEmpty();
    assertThat(result.warnings()).isEmpty();
  }

  static Stream<Arguments> brokenVersions() {
    return Stream.of(
        arguments(
            with(PROMPT, V1.promptMd().replace("{{canary}}", "").replace("{{knowledge}}", "")),
            List.of(
                "prompt is missing placeholder {{canary}}",
                "prompt is missing placeholder {{knowledge}}")),
        arguments(
            with(PROMPT, V1.promptMd() + "{{knowledge_pack}}"),
            List.of("prompt has unknown placeholder {{knowledge_pack}}")),
        arguments(with(KNOWLEDGE, " \n"), List.of("knowledge pack is empty")),
        arguments(with(SCHEMA, "{"), List.of("decision schema is not valid JSON")),
        arguments(
            with(SCHEMA, V1.schemaJson().replace("\"agent_decision\"", "\"decision\"")),
            List.of("decision schema name must be agent_decision")),
        arguments(
            with(SCHEMA, V1.schemaJson().replaceFirst("\"strict\": true", "\"strict\": false")),
            List.of("decision schema must be strict")),
        arguments(
            with(SCHEMA, "{\"name\": \"agent_decision\", \"strict\": true}"),
            List.of("decision schema must have a schema object")),
        arguments(with(TOOLS, "{}"), List.of("tools must be a JSON array")),
        arguments(
            with(TOOLS, V1.toolsJson().replace("\"book_call\"", "\"book_a_call\"")),
            List.of("tools must define get_availability and book_call")),
        arguments(
            with(TOOLS, V1.toolsJson().replaceFirst("\"strict\": true", "\"strict\": false")),
            List.of("tool get_availability must be strict")),
        arguments(
            with(
                TOOLS,
                """
                [{"name": "get_availability", "strict": true, "description": "d", "parameters": {}},
                 {"name": "book_call", "strict": true, "parameters": {}}]"""),
            List.of("tool book_call must have a description and a parameters object")),
        arguments(with(TOOLS, "null"), List.of("tools must be a JSON array")),
        arguments(
            with(TEMPLATES, "[]"), List.of("templates are not a JSON object of name to text")),
        arguments(
            with(TEMPLATES, "null"), List.of("templates are not a JSON object of name to text")),
        arguments(
            with(TEMPLATES, templatesWithout(Template.HANDOFF_ACK)),
            List.of("templates are missing HANDOFF_ACK")),
        arguments(
            with(TEMPLATES, templatesWith(Template.FALLBACK, "Sorry {nickname}?")),
            List.of("FALLBACK has unknown placeholder {nickname}")),
        arguments(
            with(
                TEMPLATES,
                templatesWith(Template.OPENER_PROVIDER, "Hi{name_part}, " + "x".repeat(240))),
            List.of("OPENER_PROVIDER renders to 325 chars, over 320")));
  }

  @ParameterizedTest
  @MethodSource("brokenVersions")
  void brokenArtifactIsAnError(PromptVersion version, List<String> errors) {
    PromptValidation.Result result = PromptValidation.validate(version, PERSONA);

    assertThat(result.errors()).containsExactlyElementsOf(errors);
    assertThat(result.ok()).isFalse();
  }

  /** The handset still gets it, in more segments: reported, not refused. */
  @Test
  void curlyQuoteIsAGsmWarning() {
    PromptValidation.Result result =
        PromptValidation.validate(
            with(TEMPLATES, templatesWith(Template.FALLBACK, "Sorry, I didn’t get that.")),
            PERSONA);

    assertThat(result.errors()).isEmpty();
    assertThat(result.warnings()).containsExactly("FALLBACK has characters outside GSM-7");
    assertThat(result.ok()).isTrue();
    assertThat(result.clean()).isFalse();
  }

  /** v1 with {@code artifact} replaced by {@code value}. */
  private static PromptVersion with(Artifact artifact, String value) {
    return new PromptVersion(
        "v1",
        Instant.EPOCH,
        PromptVersion.REPO,
        null,
        false,
        artifact == PROMPT ? value : V1.promptMd(),
        artifact == KNOWLEDGE ? value : V1.knowledgeMd(),
        artifact == SCHEMA ? value : V1.schemaJson(),
        artifact == TOOLS ? value : V1.toolsJson(),
        artifact == TEMPLATES ? value : V1.templatesJson());
  }

  private static String templatesWith(Template template, String text) {
    Map<String, String> texts = new HashMap<>(V1.parse().templates().orElseThrow());
    texts.put(template.name(), text);
    return PromptVersion.templatesJsonOf(texts);
  }

  private static String templatesWithout(Template template) {
    Map<String, String> texts = new HashMap<>(V1.parse().templates().orElseThrow());
    texts.remove(template.name());
    return PromptVersion.templatesJsonOf(texts);
  }
}
