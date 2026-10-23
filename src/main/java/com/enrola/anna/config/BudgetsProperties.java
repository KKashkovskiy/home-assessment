package com.enrola.anna.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Spend caps in USD against the sum of {@code turn.cost_usd}: across every conversation, and for
 * one. At or above either cap no model call is made; a missing cap fails startup.
 */
@Validated
@ConfigurationProperties(prefix = "anna.budgets")
public record BudgetsProperties(
    @NotNull @PositiveOrZero BigDecimal appCapUsd,
    @NotNull @PositiveOrZero BigDecimal conversationCapUsd) {}
