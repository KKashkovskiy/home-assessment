package com.enrola.anna.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.enrola.anna.config.Settings;
import com.enrola.anna.lead.Lead;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * What a template can be refused for, and what every opener must carry whatever the designer's
 * wording.
 */
class TemplatesTest {

  static final Map<String, String> SHIPPED =
      new PromptLoader(new DefaultResourceLoader())
          .load("v1", Instant.EPOCH)
          .parse()
          .templates()
          .orElseThrow();
  static final Templates DISCLOSED = templates(SHIPPED, true);

  /** The widest storable lead is the measure: the last text fits Lauren but not an 80-char name. */
  static Stream<Arguments> refusedFallbacks() {
    return Stream.of(
        arguments(
            "Sorry {nickname}, say that again?", "FALLBACK has unknown placeholder {nickname}"),
        arguments("x".repeat(161), "FALLBACK renders to 161 chars, over 160"),
        arguments("Hi{name_part}, " + "x".repeat(80), "FALLBACK renders to 165 chars, over 160"));
  }

  @ParameterizedTest
  @MethodSource("refusedFallbacks")
  void problemNamesWhatRenderWouldRefuse(String fallback, String problem) {
    assertThat(fallback(fallback).problem(Template.FALLBACK)).contains(problem);
  }

  @Test
  void fallbackThatFitsEveryLeadHasNoProblem() {
    assertThat(fallback("Hi{name_part}, " + "x".repeat(75)).problem(Template.FALLBACK)).isEmpty();
  }

  @Test
  void renderRefusesATextOverItsCap() {
    assertThatIllegalStateException()
        .isThrownBy(() -> fallback("x".repeat(161)).render(Template.FALLBACK, lead("Jo", null)))
        .withMessage("FALLBACK renders to 161 chars, over 160");
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
    assertThat(templates(SHIPPED, false).render(Template.OPENER_PROVIDER, lead("Lauren", "Bupa")))
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

  private static Templates templates(Map<String, String> texts, boolean disclosureInOpener) {
    return new Templates(texts, new Settings.Persona("Anna", "Comparato", disclosureInOpener));
  }

  /** The shipped templates with {@code text} as FALLBACK. */
  private static Templates fallback(String text) {
    Map<String, String> texts = new HashMap<>(SHIPPED);
    texts.put(Template.FALLBACK.name(), text);
    return templates(texts, true);
  }

  private static Lead lead(String name, String provider) {
    return new Lead(
        "id", name, "+61400000000", null, null, provider, null, null, null, Instant.EPOCH);
  }
}
