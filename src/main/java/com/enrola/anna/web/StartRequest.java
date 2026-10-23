package com.enrola.anna.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/conversations}: which lead to open a conversation with. */
public record StartRequest(
    @NotBlank(message = "must not be blank") @Size(max = 40, message = "must be at most 40 characters") String leadId) {}
