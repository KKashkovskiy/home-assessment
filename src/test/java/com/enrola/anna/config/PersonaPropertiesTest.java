package com.enrola.anna.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PersonaPropertiesTest {

  @EnableConfigurationProperties(PersonaProperties.class)
  static class Persona {}

  final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Persona.class);

  @ParameterizedTest
  @CsvSource({"Anna, ' ', company", "' ', Comparato, name"})
  void blankNameOrCompanyFailsStartup(String name, String company, String field) {
    runner
        .withPropertyValues("anna.persona.name=" + name, "anna.persona.company=" + company)
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("anna.persona." + field));
  }

  @Test
  void disclosureDefaultsOn() {
    runner
        .withPropertyValues("anna.persona.name=Anna", "anna.persona.company=Comparato")
        .run(
            context ->
                assertThat(context.getBean(PersonaProperties.class).disclosureInOpener()).isTrue());
  }
}
