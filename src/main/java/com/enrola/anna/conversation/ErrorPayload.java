package com.enrola.anna.conversation;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The payload of an ERROR event: a compact code, a detail that may quote the model, and for a
 * failed model call the attempts the client was allowed, null where there were none.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ErrorPayload(String code, String detail, Integer maxAttempts) {}
