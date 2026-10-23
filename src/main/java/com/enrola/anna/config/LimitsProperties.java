package com.enrola.anna.config;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The counts code enforces around the model, which the prompt also reads from the turn context.
 * {@code maxTurns} is agent messages after the opener; {@code offTopicLimit} the OFF_TOPIC or
 * INJECTION streak that ends the conversation; {@code objectionRetries} how many objections get a
 * retry before the next one ends it DECLINED. A missing limit fails startup rather than ending
 * every conversation on its first message.
 */
@Validated
@ConfigurationProperties(prefix = "anna.limits")
public record LimitsProperties(
    @Positive int maxTurns, @Positive int offTopicLimit, @PositiveOrZero int objectionRetries) {}
