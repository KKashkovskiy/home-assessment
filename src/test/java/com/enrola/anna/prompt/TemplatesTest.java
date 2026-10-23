package com.enrola.anna.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.enrola.anna.config.PersonaProperties;
import com.enrola.anna.lead.Lead;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The shipped templates against their caps and the boot checks, and what every opener must carry
 * whatever the designer's wording.
 */
class TemplatesTest {

  static final String PREFIX = "classpath:prompts/v1/templates/";
  static final Templates DISCLOSED = templates(PREFIX, true);

  @ParameterizedTest
  @EnumSource(names = {"OPENER_PROVIDER", "OPENER_NO_PROVIDER"})
  void openersFitTwoSegmentsForTheWidestStorableLead(Template template) {
    assertThat(DISCLOSED.render(template, Templates.WIDEST_STORABLE_LEAD))
        .hasSizeLessThanOrEqualTo(320);
  }

  @ParameterizedTest
  @EnumSource(
      mode = EnumSource.Mode.EXCLUDE,
      names = {"OPENER_PROVIDER", "OPENER_NO_PROVIDER"})
  void otherTemplatesFitOneSegmentForTheWidestStorableLead(Template template) {
    assertThat(DISCLOSED.render(template, Templates.WIDEST_STORABLE_LEAD))
        .hasSizeLessThanOrEqualTo(160);
  }

  static Stream<String> badFallbacks() {
    return Stream.of(
        "Sorry, I didn’t quite get that.", // curly apostrophe: not GSM-7
        "Sorry {nickname}, say that again?", // placeholder the renderer does not know
        "x".repeat(161)); // over the one-segment cap
  }

  @ParameterizedTest
  @MethodSource("badFallbacks")
  void badTemplateFailsBoot(String fallback, @TempDir Path dir) throws IOException {
    copyShippedTemplates(dir);
    Files.writeString(dir.resolve("FALLBACK.txt"), fallback);

    assertThatIllegalStateException()
        .isThrownBy(() -> templates(dir.toUri().toString(), true))
        .withMessageContaining("FALLBACK");
  }

  @Test
  void missingTemplateFailsBoot(@TempDir Path dir) throws IOException {
    copyShippedTemplates(dir);
    Files.delete(dir.resolve("FALLBACK.txt"));

    assertThatIllegalStateException()
        .isThrownBy(() -> templates(dir.toUri().toString(), true))
        .withMessageContaining("FALLBACK");
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "null",
      value = {
        "Lauren, Bupa, OPENER_PROVIDER",
        "John, HBF, OPENER_PROVIDER",
        "Jane, null, OPENER_NO_PROVIDER",
      })
  void openerCarriesIdentityAndStop(String name, String provider, Template template) {
    assertThat(DISCLOSED.render(template, lead(name, provider)))
        .contains(name, "Anna", "Comparato", "Reply STOP");
  }

  @Test
  void providerOpenerNamesTheFund() {
    assertThat(DISCLOSED.render(Template.OPENER_PROVIDER, lead("John", "HBF"))).contains("HBF");
  }

  @Test
  void disclosureOnSaysAiAssistant() {
    assertThat(DISCLOSED.render(Template.OPENER_PROVIDER, lead("Lauren", "Bupa")))
        .contains("Anna, Comparato's AI assistant");
  }

  @Test
  void disclosureOffSaysFromCompany() {
    assertThat(templates(PREFIX, false).render(Template.OPENER_PROVIDER, lead("Lauren", "Bupa")))
        .contains("Anna from Comparato")
        .doesNotContain("AI assistant");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " "})
  void blankNameLeavesNoGap(String name) {
    assertThat(DISCLOSED.render(Template.OPENER_NO_PROVIDER, lead(name, null)))
        .doesNotContain("null")
        .doesNotContain(" ,");
  }

  @Test
  void placeholderInLeadDataStaysLiteral() {
    assertThat(DISCLOSED.render(Template.OPENER_PROVIDER, lead("{persona}", "{disclosure}")))
        .contains("{persona}", "{disclosure}");
  }

  private static Templates templates(String prefix, boolean disclosureInOpener) {
    return new Templates(
        new DefaultResourceLoader(),
        prefix,
        "v1",
        new PersonaProperties("Anna", "Comparato", disclosureInOpener));
  }

  private static void copyShippedTemplates(Path dir) throws IOException {
    for (Template template : Template.values()) {
      String file = template.name() + ".txt";
      String text =
          new ClassPathResource("prompts/v1/templates/" + file)
              .getContentAsString(StandardCharsets.UTF_8);
      Files.writeString(dir.resolve(file), text);
    }
  }

  private static Lead lead(String name, String provider) {
    return new Lead(
        "id", name, "+61400000000", null, null, provider, null, null, null, Instant.EPOCH);
  }
}
