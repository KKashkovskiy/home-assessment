package com.enrola.anna.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SMS lengths. {@code targetChars} is what the prompt asks a reply to fit, {@code maxChars} the
 * hard limit it names; an inbound over {@code maxInboundChars} is stored whole, flagged, and cut to
 * that length for the model.
 */
@ConfigurationProperties(prefix = "anna.sms")
public record SmsProperties(int maxChars, int targetChars, int maxInboundChars) {}
