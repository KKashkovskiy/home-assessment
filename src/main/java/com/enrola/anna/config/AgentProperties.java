package com.enrola.anna.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the model is called. There is no key here: it comes from {@code OPENAI_API_KEY} in the
 * environment and nowhere else. {@code timeout} bounds each HTTP attempt, and the SDK retries
 * timeouts and 5xx, so a call makes up to {@code 1 + maxRetries} attempts. {@code strictModelCheck}
 * turns a failed boot-time check from a warning into a failed boot.
 */
@ConfigurationProperties(prefix = "anna.agent")
public record AgentProperties(
    String model,
    String reasoningEffort,
    String reasoningSummary,
    Duration timeout,
    int maxRetries,
    long maxOutputTokens,
    boolean strictModelCheck) {}
