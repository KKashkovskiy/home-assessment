package com.enrola.anna.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The console's live log. {@code sseEnabled} false leaves the inspector without one: the page still
 * renders every turn from the rows and a reload shows new ones. {@code sseTimeoutMin} is how long
 * one stream lives before the browser reconnects on its own.
 */
@Validated
@ConfigurationProperties(prefix = "anna.ui")
public record UiProperties(boolean sseEnabled, @Positive int sseTimeoutMin) {}
