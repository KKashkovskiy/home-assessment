package com.enrola.anna.agent;

/** One function call the model made and the JSON it was answered with. */
public record ToolCall(String name, String argumentsJson, String resultJson) {}
