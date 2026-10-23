package com.enrola.anna.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/conversations/{id}/messages}: the lead's SMS and the platform's id for
 * it, which makes a retry safe; a blank id would make unrelated messages replay each other, so it
 * is either absent or free of spaces. 4,000 characters is the transport cap; longer than {@code
 * anna.sms.max-inbound-chars} is accepted, stored whole and flagged.
 */
public record InboundRequest(
    @NotBlank(message = "must not be blank") @Size(max = 4000, message = "must be at most 4000 characters") String text,
    @Pattern(regexp = "\\S{1,80}", message = "must be 1 to 80 characters with no spaces") String providerMessageId) {}
