package com.enrola.anna.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Who is texting. {@code name} fills {persona}, {@code company} is named in the opener, and {@code
 * disclosureInOpener} (default on) picks ", Company's AI assistant" over " from Company". A blank
 * name or company fails startup rather than reaching an SMS.
 */
@Validated
@ConfigurationProperties(prefix = "anna.persona")
public record PersonaProperties(
    @NotBlank String name,
    @NotBlank String company,
    @DefaultValue("true") boolean disclosureInOpener) {}
