package com.enrola.anna.conversation;

import com.enrola.anna.scheduling.ToolResult;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The payload of a TOOL_CALL event: the arguments the model sent and the answer it read, both as
 * the JSON they were, how many slots the answer offered, how an availability answer's filtering
 * came down to them (null for any other answer), and how long the tool took.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ToolCallPayload(
    String args, String result, int slotCount, ToolResult.Filtering filtering, long latencyMs) {}
