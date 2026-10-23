package com.enrola.anna.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Moderation score thresholds above 0 and at most 1, each reached at or above its value: {@code
 * harassment} and {@code threat} end the conversation ABUSIVE, {@code selfHarm} sends the fixed
 * safety template. A threshold of 0 would trip on every message, so a missing one fails startup.
 * {@code timeout} and {@code maxRetries} bound the moderation call on its own, so a slow endpoint
 * delays a turn by seconds, not by the model's budget.
 */
@Validated
@ConfigurationProperties(prefix = "anna.moderation")
public record ModerationProperties(
    @Positive @DecimalMax("1") double harassment,
    @Positive @DecimalMax("1") double threat,
    @Positive @DecimalMax("1") double selfHarm,
    @NotNull Duration timeout,
    @PositiveOrZero int maxRetries) {}
