package com.enrola.anna.conversation;

import com.enrola.anna.guard.PreGuards;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The payload of a pre-guard step's GUARD event, which is named after the step: the outcome the
 * step decided, its type under {@code outcome} and its fields under {@code detail}; null, stored as
 * {@code {}}, for a step that passed the turn on.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PreGuardPayload(
    @JsonProperty("detail")
        @JsonTypeInfo(
            use = JsonTypeInfo.Id.SIMPLE_NAME,
            include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
            property = "outcome")
        PreGuards.Outcome outcome) {}
